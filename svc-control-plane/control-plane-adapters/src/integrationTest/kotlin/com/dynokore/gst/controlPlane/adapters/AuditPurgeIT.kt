package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.audit.AuditPurgeRequest
import com.dynokore.gst.controlPlane.adapters.audit.AuditPurgeStoreJooq
import com.dynokore.gst.controlPlane.adapters.audit.UndeterminedRetentionFloorSource
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntriesCommand
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditOutcome
import com.dynokore.gst.controlPlane.domain.audit.AuditPurgePolicy
import com.dynokore.gst.controlPlane.domain.audit.PurgeFloor
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The audited purge of `controlplane.audit_entry` as the runtime login (INV-AC-3823): the function
 * `purge_audit_entries` is the only way to delete, it writes the purge's own entry first, and the application floor
 * source answers undeterminable until evidence-svc serves the figure.
 */
class AuditPurgeIT {
    private val clock = Clock.systemUTC()
    private val tenants = TenantContextImpl()
    private val runner =
        TransactionRunnerImpl(TenantAwareDataSource(PostgresDb.runtimeDataSource), tenants, null, BINDING_KEY)
    private val dsl: DSLContext = TenancyJooq.dslContext(emptyMap(), tenants, runner)
    private val stager: AuditStager =
        LocalAuditWriter(dsl, "controlplane", "control-plane-svc", "control-plane-svc-1", clock)
    private val store =
        AuditPurgeStoreJooq(dsl, stager) { AuditPurgeRequest(REASON, "corr-purge", "cmd-purge") }

    @BeforeEach
    fun reset() {
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        admin("truncate controlplane.audit_entry")
    }

    private fun admin(sql: String) {
        PostgresDb.adminConnection().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    private fun adminCount(sql: String): Long =
        PostgresDb.adminConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun seed(
        recordedAt: Instant,
        id: UUID = UUID.randomUUID(),
    ) = admin(
        "insert into controlplane.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, instance_id, " +
            "correlation_id, causation_id, actor, action, scope) values ('$id', '$PLATFORM', 'e', " +
            "'${recordedAt.atOffset(ZoneOffset.UTC)}', '/gst/control-plane-svc', 's', 'i', 'c', 'c', 'operator-1', " +
            "'grantRole', 'x')",
    )

    private fun daysAgo(days: Long): Instant = Instant.now().minus(days, ChronoUnit.DAYS)

    private fun count() = adminCount("select count(*) from controlplane.audit_entry")

    private fun <T> asOperator(block: () -> T): T = tenants.bindPlatform(ACTOR) { runner.inTransaction(block) }

    private fun rawPurge(
        cutOff: Instant,
        entryId: String = uuidV7().toString(),
        source: String = "/gst/control-plane-svc",
        actor: DSLContext = dsl,
    ): Long =
        actor
            .select(
                DSL.function(
                    DSL.name("controlplane", "purge_audit_entries"),
                    Long::class.java,
                    DSL.`val`(cutOff.atOffset(ZoneOffset.UTC)),
                    DSL.`val`(REASON),
                    DSL.`val`(UUID.fromString(entryId)),
                    DSL.`val`(source),
                    DSL.`val`("audit-purge"),
                    DSL.`val`("control-plane-svc-1"),
                    DSL.`val`("corr-purge"),
                    DSL.`val`("cmd-purge"),
                ),
            ).fetchSingle()
            .value1()

    private fun uuidV7(): UUID {
        val millis = System.currentTimeMillis()
        val random = UUID.randomUUID()
        val high = (millis shl 16) or 0x7000L or (random.mostSignificantBits and 0x0FFFL)
        val low = (random.leastSignificantBits and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(high, low)
    }

    private fun useCase(floors: AuditPurgeFloorSource) = PurgeAuditEntries({ _, _, _ -> true }, floors, store, clock)

    @Test
    fun `INV-AC-3823 the runtime login cannot DELETE from audit_entry directly`() {
        seed(daysAgo(YEARS_7))
        PostgresDb.probe.fetchValue(
            "select has_table_privilege(current_user, 'controlplane.audit_entry', 'DELETE')",
        ) shouldBe false
        PostgresDb.probe.fetchValue(
            "select has_table_privilege(current_user, 'controlplane.audit_entry', 'SELECT')",
        ) shouldBe true
        val failure =
            shouldThrow<DataAccessException> {
                unboundDsl().execute("delete from controlplane.audit_entry")
            }
        failure.sqlState() shouldBe "42501"
        count() shouldBe 1
    }

    @Test
    fun `INV-AC-3823 purge_audit_entries refuses a cut-off younger than 5 years even by raw SQL`() {
        seed(daysAgo(YEARS_7))
        val young =
            shouldThrow<DataAccessException> {
                asOperator { rawPurge(daysAgo(YEARS_4)) }
            }
        young.sqlState() shouldBe "22023"
        count() shouldBe 1
        asOperator { rawPurge(daysAgo(YEARS_6)) } shouldBe 1L
    }

    @Test
    fun `INV-AC-3823 the purge deletes only older rows and leaves its own entry with the user ID as actor`() {
        seed(daysAgo(YEARS_11))
        seed(daysAgo(YEARS_6))
        seed(daysAgo(YEARS_4))
        val result = asOperator { store.purge(ACTOR, daysAgo(YEARS_6 - 1)) }
        result.deletedCount shouldBe 2
        count() shouldBe 2
        adminCount(
            "select count(*) from controlplane.audit_entry where recorded_at < now() - interval '5 years'",
        ) shouldBe
            0
        adminCount(
            "select count(*) from controlplane.audit_entry where id = '${result.auditEntryId}' and actor = '$ACTOR' " +
                "and action = 'purgeAuditEntries' and reason = '$REASON' and tenant_id = '$PLATFORM' " +
                "and (prior_state->>'rowCount')::int = 2",
        ) shouldBe 1
        adminCount(
            "select count(*) from controlplane.audit_entry where actor in ('platform.engineer', 'tenant.admin')",
        ) shouldBe
            0
    }

    @Test
    fun `INV-AC-3823 a failed audit write deletes nothing`() {
        seed(daysAgo(YEARS_7))
        val cutOff = daysAgo(YEARS_6)
        val existing = uuidV7().also { seed(Instant.now(), it) }
        val failures =
            listOf<() -> Long>(
                { asOperator { rawPurge(cutOff, entryId = uuidV7().toString(), source = " ") } },
                { asOperator { rawPurge(cutOff, entryId = UUID.randomUUID().toString()) } },
                { asOperator { rawPurge(cutOff, entryId = existing.toString()) } },
                // app.actor unset: no binding at all, so bound_actor() is NULL
                { rawPurge(cutOff, entryId = uuidV7().toString(), actor = unboundDsl()) },
            )
        failures.forEach { shouldThrow<DataAccessException> { it() } }
        count() shouldBe 2
    }

    @Test
    fun `INV-AC-3823 an own entry that cannot be written deletes nothing`() {
        seed(daysAgo(YEARS_7))
        admin("revoke insert on controlplane.audit_entry from controlplane_sys")
        try {
            shouldThrow<DataAccessException> { asOperator { rawPurge(daysAgo(YEARS_6)) } }
        } finally {
            admin("grant insert on controlplane.audit_entry to controlplane_sys")
        }
        count() shouldBe 1
    }

    private fun unboundDsl(): DSLContext = DSL.using(PostgresDb.runtimeDataSource, org.jooq.SQLDialect.POSTGRES)

    @Test
    fun `INV-AC-3823 with the bound floor source the use case answers FloorUndeterminable and deletes nothing`() {
        seed(daysAgo(YEARS_11))
        val outcome =
            asOperator {
                useCase(UndeterminedRetentionFloorSource()).execute(
                    PurgeAuditEntriesCommand(
                        ACTOR,
                        daysAgo(YEARS_11 - 1),
                    ),
                )
            }
        outcome shouldBe PurgeAuditOutcome.FloorUndeterminable
        count() shouldBe 1
    }

    @Test
    fun `INV-AC-3823 a stub floor source with P6Y and P2200D gives the longer one, P2200D`() {
        val figures = listOf<Period?>(Period.ofYears(SIX), Period.ofDays(DAYS_2200))
        val floor = AuditPurgePolicy.floor(figures, Instant.now())
        floor.shouldBeInstanceOf<PurgeFloor.Determined>().retention shouldBe Period.ofDays(DAYS_2200)
        seed(daysAgo(YEARS_11))
        val source = AuditPurgeFloorSource { figures }
        // 2199 days back is older than P6Y but younger than P2200D: refused
        asOperator { useCase(source).execute(PurgeAuditEntriesCommand(ACTOR, daysAgo(DAYS_2200 - 1L))) } shouldBe
            PurgeAuditOutcome.CutOffYoungerThanFloor
        count() shouldBe 1
        asOperator { useCase(source).execute(PurgeAuditEntriesCommand(ACTOR, daysAgo(DAYS_2200 + 1L))) }
            .shouldBeInstanceOf<PurgeAuditOutcome.Purged>()
            .result.deletedCount shouldBe 1
    }

    private companion object {
        const val ACTOR = "operator-9"
        const val REASON = "K1"
        val PLATFORM: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        const val YEARS_4 = 4L * 365
        const val YEARS_6 = 6L * 366
        const val YEARS_7 = 7L * 366
        const val YEARS_11 = 11L * 366
        const val SIX = 6
        const val DAYS_2200 = 2200
        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }
}
