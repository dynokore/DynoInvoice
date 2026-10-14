package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.APP
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.SYS
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * INV-T-4616 (coordinator rulings INV-Q-4614 (A), INV-Q-4622 (A)): `purge_audit_entries` and every CHECK of
 * audit-log's tables use tenancy-kernel's shared blank and actor definitions. Whitespace-only values (ISO controls and
 * the caseId whitespace set) are refused through each argument and the `app.actor` read with the function's own error,
 * and by each CHECK on a direct insert as the owner; an actor holding a control character is refused the same way;
 * nothing is written or deleted. A value with a visible character is stored unchanged. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlankValuesIT {
    private val fixtures = mutableMapOf<String, Fixture>()

    @AfterAll
    fun close() = fixtures.values.forEach { it.close() }

    private fun fixture(image: String) = fixtures.getOrPut(image) { Fixture(image) }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `purge_audit_entries refuses a blank or control actor and blank arguments, deleting nothing`(image: String) {
        val f = fixture(image)
        f.seedOld()
        val missed = mutableListOf<String>()

        fun probe(
            name: String,
            message: String,
            state: String,
            actor: String = "operator-9",
            edit: (List<String>) -> List<String> = { it },
        ) {
            val before = f.count("select count(*) from $SCHEMA.audit_entry")
            val refused = runCatching { f.rawPurge(actor, edit(f.stagedArguments())) }.exceptionOrNull()
            if (refused !is SQLException || refused.sqlState != state || message !in refused.message.orEmpty()) {
                missed += "$name: ${refused?.message ?: "accepted"}"
            }
            assertEquals(before, f.count("select count(*) from $SCHEMA.audit_entry"), "$name wrote or deleted")
        }
        NOT_ACTORS.forEach { (name, actor) -> probe("actor $name", "app.actor is not set", "42501", actor) }
        BLANKS.forEach { (name, value) ->
            probe("reason $name", "needs a reason", "22023") { it.with(REASON, value) }
            listOf(SUBJECT, INSTANCE, CORRELATION, CAUSATION).forEach { index ->
                probe("argument $index $name", "needs a subject, instance, correlation and causation", "22023") {
                    it.with(index, value)
                }
            }
        }
        assertEquals(emptyList<String>(), missed)

        VISIBLE_ACTORS.forEach { actor ->
            val arguments = f.stagedArguments()
            f.rawPurge(actor, arguments)
            assertEquals(
                actor,
                f.string("select actor from $SCHEMA.audit_entry where id = ?::uuid", arguments[ENTRY_ID]),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `every CHECK refuses a blank value, and an actor CHECK a control character, by a direct owner insert`(
        image: String,
    ) {
        val f = fixture(image)
        val tenant = UUID.randomUUID()
        val missed = mutableListOf<String>()
        INSERTS.forEach { (table, sql, columns) ->
            columns.forEach { (column, actor) ->
                (if (actor) NOT_ACTORS else BLANKS).forEach { (name, value) ->
                    val before = f.count("select count(*) from $SCHEMA.$table")
                    val refused = runCatching { f.ownerInsert(sql, tenant, columns, column, value) }.exceptionOrNull()
                    if (refused !is SQLException || refused.sqlState != "23514") missed += "$table.$column $name"
                    assertEquals(before, f.count("select count(*) from $SCHEMA.$table"), "$table.$column $name")
                }
                (if (actor) VISIBLE_ACTORS else VISIBLE_ACTORS + " \t x \n ").forEach { value ->
                    val id = f.ownerInsert(sql, tenant, columns, column, value)
                    assertEquals(value, f.string("select $column from $SCHEMA.$table where id = ?::uuid", "$id"))
                }
            }
        }
        assertEquals(emptyList<String>(), missed)
        val entry = INSERTS.single { it.first == "audit_entry" }
        f.ownerInsert(entry.second, tenant, entry.third, "reason", null)
    }

    private fun List<String>.with(
        index: Int,
        value: String,
    ) = toMutableList().also { it[index] = value }

    /** The fixture `control-plane-svc`: the audit table, the purge function and the override grant table. */
    private class Fixture(
        image: String,
    ) : AutoCloseable {
        val fixture = OutboxPostgresFixture(image)
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)

        init {
            fixture.runAsAdmin(
                "create schema $SCHEMA authorization outbox_owner; grant usage on schema $SCHEMA to $APP;",
            )
            fixture.run(
                AuditSchema.auditEntryDdl(SCHEMA, APP) + "\n" +
                    AuditSchema.purgeDdl(SCHEMA, APP, SYS, "control-plane-svc") + "\n" +
                    AuditSchema.ddl(SCHEMA, APP),
            )
        }

        /** An entry eight years old, which a purge that passed its checks would delete. */
        fun seedOld() =
            fixture.adminDataSource().connection.use { c ->
                c
                    .prepareStatement(
                        "insert into $SCHEMA.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, " +
                            "instance_id, correlation_id, causation_id, actor, action, scope) values " +
                            "(gen_random_uuid(), ?, 'e', ?, '/gst/control-plane-svc', 's', 'i', 'c', 'c', " +
                            "'operator-1', 'grantRole', 'x')",
                    ).use {
                        it.setObject(1, TenantId.PLATFORM.value)
                        val eightYears = ZonedDateTime.now(ZoneOffset.UTC).minusYears(8).toInstant()
                        it.setTimestamp(2, Timestamp.from(eightYears))
                        it.executeUpdate()
                    }
            }

        /** The text arguments of `purge_audit_entries` after the cut-off, for a freshly staged entry. */
        fun stagedArguments(): List<String> {
            val staged =
                LocalAuditWriter(tx, SCHEMA, "control-plane-svc", "control-plane-svc-1", Clock.systemUTC()).stage(
                    AuditEntry("operator-9", AuditSchema.PURGE_ACTION, "platform (audit_entry)", "idem-key-1"),
                    EventContext(
                        tenant = TenantId.PLATFORM,
                        entity = EntityRef.PLATFORM,
                        docUuid = null,
                        transactionDate = null,
                        aggregateKey = "audit-purge",
                        aggregateVersion = null,
                        correlation = CorrelationId("corr-purge"),
                        causation = CausationId("cmd-purge"),
                    ),
                )
            val e = staged.envelope
            return listOf(
                checkNotNull(staged.entry.reason),
                e.id.value,
                e.source,
                e.subject,
                e.instanceId,
                e.correlation.value,
                e.causation.value,
            )
        }

        /** Calls the purge as the runtime login with the platform tenant and [actor] set by `set_config`. */
        fun rawPurge(
            actor: String,
            arguments: List<String>,
        ) = fixture.runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(BIND).use {
                    it.setString(1, TenantId.PLATFORM.value.toString())
                    it.setString(2, actor)
                    it.execute()
                }
                c.prepareStatement("select $SCHEMA.purge_audit_entries(?, ?, ?::uuid, ?, ?, ?, ?, ?)").use {
                    it.setTimestamp(1, Timestamp.from(ZonedDateTime.now(ZoneOffset.UTC).minusYears(6).toInstant()))
                    arguments.forEachIndexed { i, value -> it.setString(i + 2, value) }
                    it.executeQuery().use { rs -> rs.next() }
                }
                c.commit()
            } catch (failure: SQLException) {
                c.rollback()
                throw failure
            }
        }

        /** Inserts one row as the owner, [column] set to [value] and every other probed column valid. */
        fun ownerInsert(
            sql: String,
            tenant: UUID,
            columns: List<Pair<String, Boolean>>,
            column: String,
            value: String?,
        ): UUID {
            val id = UUID.randomUUID()
            fixture.ownerDataSource().connection.use { c ->
                c.createStatement().use { it.execute("select set_config('app.tenant_id', '$tenant', false)") }
                c.prepareStatement(sql).use { s ->
                    s.setObject(1, id)
                    s.setObject(2, tenant)
                    columns.forEachIndexed { i, (name, _) -> s.setString(i + 3, if (name == column) value else "v") }
                    s.executeUpdate()
                }
            }
            return id
        }

        fun count(sql: String): Long = string(sql)!!.toLong()

        fun string(
            sql: String,
            vararg args: Any,
        ): String? =
            fixture.adminDataSource().connection.use { c ->
                c.prepareStatement(sql).use { s ->
                    args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
                    s.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
            }

        override fun close() = fixture.close()
    }

    private companion object {
        const val SCHEMA = "controlplane"
        const val BIND = "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)"
        const val REASON = 0
        const val ENTRY_ID = 1
        const val SUBJECT = 3
        const val INSTANCE = 4
        const val CORRELATION = 5
        const val CAUSATION = 6

        /** Whitespace-only values: ISO controls and the caseId whitespace set (INV-Q-4614), alone and mixed. */
        val BLANKS =
            listOf(
                "tab" to "\t",
                "newline" to "\n",
                "CR" to "\r",
                "U+0085" to "\u0085",
                "U+00A0" to "\u00A0",
                "U+1680" to "\u1680",
                "U+2000" to "\u2000",
                "U+200A" to "\u200A",
                "U+2028" to "\u2028",
                "U+2029" to "\u2029",
                "U+202F" to "\u202F",
                "U+205F" to "\u205F",
                "U+3000" to "\u3000",
                "U+FEFF" to "\uFEFF",
                "U+0001" to "\u0001",
                "U+009F" to "\u009F",
                "mix" to " \u00A0\t\u3000\r\n\uFEFF",
            )

        /** Blank values and actors holding a control character next to visible ones (INV-Q-4622). */
        val NOT_ACTORS =
            BLANKS +
                listOf(
                    "a TAB b" to "a\tb",
                    "LF x" to "\nx",
                    "x CR" to "x\r",
                    "x U+0085" to "x\u0085",
                    "ESC [31mx" to "\u001B[31mx",
                    "x U+009B" to "x\u009B",
                )

        /** Actors with a visible character, stored unchanged. */
        val VISIBLE_ACTORS = listOf("x\u200By", "CASE 5521", "a".repeat(254) + "z")

        /** Each table's owner insert (id, tenant, then the probed columns) and its probed columns (true: an actor). */
        val INSERTS =
            listOf(
                Triple(
                    "audit_entry",
                    "insert into $SCHEMA.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, " +
                        "instance_id, correlation_id, causation_id, actor, action, scope, reason) values " +
                        "(?::uuid, ?, 'e', now(), '/gst/control-plane-svc', 's', 'i', 'c', 'c', ?, ?, ?, ?)",
                    listOf("actor" to true, "action" to false, "scope" to false, "reason" to false),
                ),
                Triple(
                    "override_grant",
                    "insert into $SCHEMA.override_grant (id, tenant_id, doc_uuid, document_version, error_code, " +
                        "actor, reason, granted_at, audit_event_id) values " +
                        "(?::uuid, ?, gen_random_uuid(), 1, 'MDM-003', ?, ?, now(), 'evt-1')",
                    listOf("actor" to true, "reason" to false),
                ),
            )
    }
}
