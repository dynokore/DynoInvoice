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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream

/**
 * The audited purge of `audit_entry` ([AuditSchema.purgeDdl]; INV-T-3984, INV-API-3822) on PostgreSQL 17 and 18, for a
 * fixture `control-plane-svc` (schema `controlplane`, runtime login `outbox_app`, NOLOGIN `outbox_sys`). The purge
 * entry is staged through [LocalAuditWriter.stage] (coordinator ruling INV-Q-4277) and the function is called as the
 * runtime login under `bindPlatform(actor)`, which sets `app.actor` (INV-Q-3997).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditPurgeIT {
    private val fixtures = mutableMapOf<String, Purge>()

    private fun purge(image: String): Purge = fixtures.getOrPut(image) { Purge(image) }.also { it.reset() }

    @AfterAll
    fun close() = fixtures.values.forEach { it.close() }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the purge writes its own entry and deletes only the entries older than the cut-off`(image: String) {
        val p = purge(image)
        val tenant = UUID.randomUUID()
        p.seed(PLATFORM, p.yearsAgo(7))
        p.seed(PLATFORM, p.yearsAgo(6))
        p.seed(tenant, p.yearsAgo(8))
        p.seed(PLATFORM, p.yearsAgo(1))
        p.seed(tenant, p.now.minusSeconds(DAY))
        val cutOff = p.yearsAgo(5).minusSeconds(DAY)
        val staged = p.stage()

        assertEquals(3L, p.call(PurgeCall.of(staged, cutOff)))

        assertEquals(3L, p.count("select count(*) from controlplane.audit_entry"))
        val older = "select count(*) from controlplane.audit_entry where recorded_at < ?"
        assertEquals(0L, p.count(older, Timestamp.from(cutOff)))
        val own =
            "select count(*) from controlplane.audit_entry where id = ?::uuid and tenant_id = ? and entity_ref = ? " +
                "and actor = 'operator-9' and action = 'purgeAuditEntries' " +
                "and scope = 'platform (control-plane-svc audit_entry)' and reason = 'idem-key-1' " +
                "and source = '/gst/control-plane-svc' and subject = 'audit-purge' " +
                "and instance_id = 'control-plane-svc-1' and correlation_id = 'corr-purge' " +
                "and causation_id = 'cmd-purge' and (prior_state->>'rowCount')::int = 3 " +
                "and (prior_state->>'oldest')::timestamptz = ? and (prior_state->>'newest')::timestamptz = ? " +
                "and (prior_state->>'cutOff')::timestamptz = ?"
        assertEquals(
            1L,
            p.count(
                own,
                staged.envelope.id.value,
                TenantId.PLATFORM.value,
                EntityRef.PLATFORM.value,
                Timestamp.from(p.yearsAgo(8)),
                Timestamp.from(p.yearsAgo(6)),
                Timestamp.from(cutOff),
            ),
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a purge with nothing to delete still writes its entry, with no prior state`(image: String) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(1))
        val staged = p.stage()
        assertEquals(0L, p.call(PurgeCall.of(staged, p.yearsAgo(6))))
        assertEquals(2L, p.count("select count(*) from controlplane.audit_entry"))
        val prior = "select prior_state::text from controlplane.audit_entry where id = ?::uuid"
        assertNull(p.strings(prior, staged.envelope.id.value).single())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a purge whose own entry cannot be written deletes nothing`(image: String) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(7))
        p.fixture.runAsAdmin("revoke insert on controlplane.audit_entry from $SYS")
        try {
            assertThrows(SQLException::class.java) { p.call(PurgeCall.of(p.stage(), p.yearsAgo(6))) }
        } finally {
            p.fixture.runAsAdmin("grant insert on controlplane.audit_entry to $SYS")
        }
        assertEquals(1L, p.count("select count(*) from controlplane.audit_entry"))
    }

    /** One test per check of the function (INV-T-3984, INV-Q-4277): each refuses, and nothing is written or deleted. */
    @ParameterizedTest(name = "{1} [{0}]")
    @MethodSource("refusals")
    fun `a failed check refuses and deletes nothing`(
        image: String,
        check: String,
    ) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(7))
        p.seed(UUID.randomUUID(), p.yearsAgo(9))
        val staged = p.stage()
        val good = PurgeCall.of(staged, p.yearsAgo(6))
        if (check == ALREADY_PRESENT) p.seed(PLATFORM, p.now, id = staged.envelope.id.value)
        val failure = assertThrows(SQLException::class.java) { checkNotNull(REFUSALS[check])(p, good) }
        val expected = if (check in PRIVILEGE_CHECKS) "42501" else "22023"
        assertEquals(expected, failure.sqlState, failure.message)
        val seeded = if (check == ALREADY_PRESENT) 3L else 2L
        assertEquals(seeded, p.count("select count(*) from controlplane.audit_entry"))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the runtime login holds only SELECT and INSERT, cannot delete, and raw SQL cannot pass the floor`(
        image: String,
    ) {
        val p = purge(image)
        assertEquals(
            listOf("INSERT,SELECT"),
            p.strings(
                "select string_agg(privilege_type, ',' order by privilege_type) " +
                    "from information_schema.role_table_grants " +
                    "where grantee = '$APP' and table_schema = 'controlplane' and table_name = 'audit_entry'",
            ),
        )
        assertEquals(
            listOf("purge_audit_entries|$SYS|search_path=pg_catalog, controlplane, pg_temp|$APP,$SYS"),
            p.strings(
                "select p.proname || '|' || pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ';') " +
                    "|| '|' || (select string_agg(distinct split_part(a::text, '=', 1), ',' " +
                    "order by split_part(a::text, '=', 1)) from unnest(p.proacl) a) " +
                    "from pg_proc p where p.pronamespace = 'controlplane'::regnamespace and p.prosecdef",
            ),
        )
        assertEquals(listOf("false"), p.strings("select has_schema_privilege('$SYS', 'controlplane', 'CREATE')::text"))

        p.seed(PLATFORM, p.yearsAgo(7))
        val delete = assertThrows(SQLException::class.java) { p.raw("delete from controlplane.audit_entry") }
        assertEquals("42501", delete.sqlState)
        val young =
            assertThrows(SQLException::class.java) {
                p.raw(
                    "select controlplane.purge_audit_entries(now() - interval '4 years', 'raw', " +
                        "'${p.stage().envelope.id.value}'::uuid, now(), '/gst/control-plane-svc', 's', 'i', 'c', 'c')",
                )
            }
        assertEquals("22023", young.sqlState)
        assertEquals(1L, p.count("select count(*) from controlplane.audit_entry"))
    }

    fun refusals(): Stream<Arguments> = IMAGES
        .flatMap { image -> REFUSALS.keys.map { Arguments.of(image, it) } }
        .stream()

    /** The arguments of one `purge_audit_entries` call. */
    private data class PurgeCall(
        val cutOff: Instant,
        val reason: String,
        val entryId: String,
        val recordedAt: Instant,
        val source: String,
        val subject: String,
        val instanceId: String,
        val correlationId: String,
        val causationId: String,
    ) {
        companion object {
            fun of(
                staged: StagedAuditEntry,
                cutOff: Instant,
            ) = PurgeCall(
                cutOff = cutOff,
                reason = checkNotNull(staged.entry.reason),
                entryId = staged.envelope.id.value,
                recordedAt = staged.envelope.time,
                source = staged.envelope.source,
                subject = staged.envelope.subject,
                instanceId = staged.envelope.instanceId,
                correlationId = staged.envelope.correlation.value,
                causationId = staged.envelope.causation.value,
            )
        }
    }

    /** The fixture `control-plane-svc` with the audit table and the purge function, on one container. */
    private class Purge(image: String) : AutoCloseable {
        val fixture = OutboxPostgresFixture(image)
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
        val writer = LocalAuditWriter(tx, SCHEMA, "control-plane-svc", "control-plane-svc-1", Clock.systemUTC())

        /** The time the rows are aged against, a minute behind the database's `now()`. */
        val now: Instant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)

        init {
            fixture.runAsAdmin(
                "create schema $SCHEMA authorization outbox_owner; grant usage on schema $SCHEMA to $APP;",
            )
            fixture.run(
                AuditSchema.auditEntryDdl(SCHEMA, APP) + "\n" +
                    AuditSchema.purgeDdl(SCHEMA, APP, SYS, "control-plane-svc"),
            )
        }

        fun reset() = fixture.runAsAdmin("truncate $SCHEMA.audit_entry")

        fun yearsAgo(years: Long): Instant = ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(years).toInstant()

        /** The purge's own entry, staged as control-plane-svc would stage it. */
        fun stage(): StagedAuditEntry = writer.stage(
            AuditEntry("operator-9", AuditSchema.PURGE_ACTION, SCOPE, "idem-key-1"),
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

        /** An existing entry of [tenant] recorded at [at], written as the superuser. */
        fun seed(
            tenant: UUID,
            at: Instant,
            id: String = UUID.randomUUID().toString(),
        ) = fixture.adminDataSource().connection.use { c ->
            c.prepareStatement(
                "insert into $SCHEMA.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, " +
                    "instance_id, correlation_id, causation_id, actor, action, scope) " +
                    "values (?::uuid, ?, 'e', ?, '/gst/control-plane-svc', 's', 'i', 'c', 'c', " +
                    "'operator-1', 'grantRole', 'x')",
            ).use {
                it.setString(1, id)
                it.setObject(2, tenant)
                it.setTimestamp(3, Timestamp.from(at))
                it.executeUpdate()
            }
        }

        /** Calls the function as the runtime login, bound to the platform tenant (or [tenant]) and [actor]. */
        fun call(
            call: PurgeCall,
            actor: String? = "operator-9",
            tenant: UUID? = null,
        ): Long = bound(actor, tenant) {
            tx.require().prepareStatement("select $SCHEMA.purge_audit_entries(?, ?, ?::uuid, ?, ?, ?, ?, ?, ?)").use {
                it.setTimestamp(1, Timestamp.from(call.cutOff))
                it.setString(2, call.reason)
                it.setString(3, call.entryId)
                it.setTimestamp(4, Timestamp.from(call.recordedAt))
                it.setString(5, call.source)
                it.setString(6, call.subject)
                it.setString(7, call.instanceId)
                it.setString(8, call.correlationId)
                it.setString(9, call.causationId)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

        /** Runs [sql] as the runtime login under the platform tenant and an actor. */
        fun raw(sql: String) = bound("operator-9", null) { tx.require().createStatement().use { it.execute(sql) } }

        // INV-T-3999 removes the actor-less binding
        @Suppress("DEPRECATION")
        private fun <T> bound(
            actor: String?,
            tenant: UUID?,
            block: () -> T,
        ): T = when {
            tenant != null -> tenants.bind(TenantId(tenant), checkNotNull(actor)) { tx.inTransaction(block) }
            actor == null -> tenants.bindPlatform { tx.inTransaction(block) }
            else -> tenants.bindPlatform(actor) { tx.inTransaction(block) }
        }

        fun count(
            sql: String,
            vararg args: Any,
        ): Long = strings(sql, *args).single()!!.toLong()

        fun strings(
            sql: String,
            vararg args: Any,
        ): List<String?> = fixture.adminDataSource().connection.use { c ->
            c.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

        override fun close() = fixture.close()
    }

    private companion object {
        const val SCHEMA = "controlplane"
        const val SCOPE = "platform (control-plane-svc audit_entry)"
        const val DAY = 86_400L
        val PLATFORM: UUID = TenantId.PLATFORM.value
        val IMAGES = listOf("postgres:17", "postgres:18")
        val PRIVILEGE_CHECKS = setOf("actor unset", "tenant not the platform tenant")
        const val ALREADY_PRESENT = "entry id already present"

        /** Each check of the function, and the call that fails only that check. */
        val REFUSALS: Map<String, (Purge, PurgeCall) -> Long> =
            mapOf(
                "actor unset" to { p, call -> p.call(call, actor = null) },
                "tenant not the platform tenant" to { p, call -> p.call(call, tenant = UUID.randomUUID()) },
                "cut-off younger than the legal floor" to
                    { p, call -> p.call(call.copy(cutOff = p.yearsAgo(5).plusSeconds(DAY))) },
                "blank reason" to { p, call -> p.call(call.copy(reason = " ")) },
                "blank subject" to { p, call -> p.call(call.copy(subject = "")) },
                "blank instance" to { p, call -> p.call(call.copy(instanceId = " ")) },
                "blank correlation" to { p, call -> p.call(call.copy(correlationId = "")) },
                "blank causation" to { p, call -> p.call(call.copy(causationId = " ")) },
                "source of another service" to { p, call -> p.call(call.copy(source = "/gst/tenancy-svc")) },
                "entry id not a version-7 UUID" to
                    { p, call -> p.call(call.copy(entryId = UUID.randomUUID().toString())) },
                ALREADY_PRESENT to { p, call -> p.call(call) },
                "recorded time not after the cut-off" to { p, call -> p.call(call.copy(recordedAt = p.yearsAgo(6))) },
            )
    }
}
