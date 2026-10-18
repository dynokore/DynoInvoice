package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.APP
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.SYS
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * INV-T-4616 (coordinator rulings INV-Q-4614 (A), INV-Q-4622 (A)): `purge_audit_entries` and every CHECK of
 * audit-log's tables use tenancy-kernel's shared blank and actor definitions. Whitespace-only values (ISO controls and
 * the caseId whitespace set) are refused through each argument and the bound-actor read with the function's own error,
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
            val before = f.platformEntries()
            val refused = runCatching { f.rawPurge(actor, edit(f.stagedArguments())) }.exceptionOrNull()
            if (refused == null || f.db.tx.sqlStateOf(refused) != state || message !in refused.message.orEmpty()) {
                missed += "$name: ${refused?.message ?: "accepted"}"
            }
            assertEquals(before, f.platformEntries(), "$name wrote or deleted")
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
                f.db.text(
                    "select actor from $SCHEMA.audit_entry where id = ?::uuid",
                    TenantId.PLATFORM.value,
                    UUID.fromString(arguments[ENTRY_ID]),
                ),
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
        INSERTS.forEach { (table, fixed, columns) ->
            columns.forEach { (column, actor) ->
                (if (actor) NOT_ACTORS else BLANKS).forEach { (name, value) ->
                    val before = f.count("select count(*) from $SCHEMA.$table", tenant)
                    val refused =
                        runCatching {
                            f.ownerInsert(
                                table,
                                fixed,
                                tenant,
                                columns,
                                column,
                                value,
                            )
                        }.exceptionOrNull()
                    if (refused == null || f.db.tx.sqlStateOf(refused) != "23514") missed += "$table.$column $name"
                    assertEquals(before, f.count("select count(*) from $SCHEMA.$table", tenant), "$table.$column $name")
                }
                (if (actor) VISIBLE_ACTORS else VISIBLE_ACTORS + " \t x \n ").forEach { value ->
                    val id = f.ownerInsert(table, fixed, tenant, columns, column, value)
                    assertEquals(value, f.db.text("select $column from $SCHEMA.$table where id = ?::uuid", tenant, id))
                }
            }
        }
        assertEquals(emptyList<String>(), missed)
        val entry = INSERTS.single { it.first == "audit_entry" }
        f.ownerInsert(entry.first, entry.second, tenant, entry.third, "reason", null)
    }

    private fun List<String>.with(
        index: Int,
        value: String,
    ) = toMutableList().also { it[index] = value }

    /** The fixture `control-plane-svc`: the audit table, the purge function and the override grant table. */
    private class Fixture(
        image: String,
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                image,
                "reset role;\ncreate schema $SCHEMA authorization outbox_owner;\n" +
                    "grant usage on schema $SCHEMA to $APP;\n" + AuditTestDb.bindingInit(SCHEMA, APP, SYS) +
                    "set role outbox_owner;\n" +
                    AuditSchema.auditEntryDdl(SCHEMA, APP) + "\n" +
                    AuditSchema.purgeDdl(SCHEMA, APP, SYS, "control-plane-svc") + "\n" +
                    AuditSchema.ddl(SCHEMA, APP),
            )
        val db = AuditTestDb(fixture, SignedBinding.keys())
        val tenants = db.tenants
        private val ownerDsl =
            db.dsl(
                db.ownerTx,
                mapOf("$SCHEMA.audit_entry" to TableClass.TENANT, "$SCHEMA.override_grant" to TableClass.TENANT),
            )

        /** An entry eight years old, which a purge that passed its checks would delete. */
        fun seedOld() =
            db.ownerInsert(
                ownerDsl,
                TenantId.PLATFORM,
                SCHEMA,
                "audit_entry",
                mapOf(
                    "id" to UUID.randomUUID(),
                    "tenant_id" to TenantId.PLATFORM.value,
                    "entity_ref" to "e",
                    "recorded_at" to ZonedDateTime.now(ZoneOffset.UTC).minusYears(8).toOffsetDateTime(),
                    "source" to "/gst/control-plane-svc",
                    "subject" to "s",
                    "instance_id" to "i",
                    "correlation_id" to "c",
                    "causation_id" to "c",
                    "actor" to "operator-1",
                    "action" to "grantRole",
                    "scope" to "x",
                ),
            )

        fun platformEntries(): Long = count("select count(*) from $SCHEMA.audit_entry", TenantId.PLATFORM.value)

        /** The text arguments of `purge_audit_entries` after the cut-off, for a freshly staged entry. */
        fun stagedArguments(): List<String> {
            val staged =
                LocalAuditWriter(
                    db.dsl(db.tx, emptyMap()),
                    SCHEMA,
                    "control-plane-svc",
                    "control-plane-svc-1",
                    Clock.systemUTC(),
                ).stage(
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

        /** Calls the purge as the runtime login with the platform tenant and a binding signed for [actor]. */
        fun rawPurge(
            actor: String,
            arguments: List<String>,
        ) = db.signed(
            TenantId.PLATFORM,
            "operator-9",
            signature = SignedBinding.value(SignedBinding.key(), TenantId.PLATFORM, actor),
        ) {
            select(
                DSL.function(
                    DSL.name(SCHEMA, "purge_audit_entries"),
                    Long::class.java,
                    DSL.`val`(ZonedDateTime.now(ZoneOffset.UTC).minusYears(6).toOffsetDateTime()),
                    DSL.`val`(arguments[0]),
                    DSL.`val`(UUID.fromString(arguments[1])),
                    *arguments.drop(2).map { DSL.`val`(it) }.toTypedArray(),
                ),
            ).fetchSingle().value1()
        }

        /** Inserts one row as the owner, [column] set to [value] and every other probed column valid. */
        fun ownerInsert(
            table: String,
            fixed: Map<String, Any>,
            tenant: UUID,
            columns: List<Pair<String, Boolean>>,
            column: String,
            value: String?,
        ): UUID {
            val id = UUID.randomUUID()
            // a grant row needs a fresh document for its unique key, as gen_random_uuid() gave one before
            val fresh = if (table == "override_grant") mapOf("doc_uuid" to UUID.randomUUID()) else emptyMap()
            val probed = columns.associate { (name, _) -> name to (if (name == column) value else "v") }
            db.ownerInsert(
                ownerDsl,
                TenantId(tenant),
                SCHEMA,
                table,
                mapOf("id" to id, "tenant_id" to tenant) + fixed + fresh + probed,
            )
            return id
        }

        fun count(
            sql: String,
            tenant: UUID,
        ): Long = db.count(sql, listOf(tenant))

        override fun close() = db.close()
    }

    private companion object {
        const val SCHEMA = "controlplane"
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

        /** Each table's owner insert (its fixed columns) and its probed columns (true: an actor). */
        val INSERTS =
            listOf(
                Triple(
                    "audit_entry",
                    mapOf(
                        "entity_ref" to "e",
                        "recorded_at" to OffsetDateTime.now(ZoneOffset.UTC),
                        "source" to "/gst/control-plane-svc",
                        "subject" to "s",
                        "instance_id" to "i",
                        "correlation_id" to "c",
                        "causation_id" to "c",
                    ),
                    listOf("actor" to true, "action" to false, "scope" to false, "reason" to false),
                ),
                Triple(
                    "override_grant",
                    mapOf(
                        "document_version" to 1L,
                        "error_code" to "MDM-003",
                        "granted_at" to OffsetDateTime.now(ZoneOffset.UTC),
                        "audit_event_id" to "evt-1",
                        "entity_ref" to "entity-1",
                    ),
                    listOf("actor" to true, "reason" to false),
                ),
            )
    }
}
