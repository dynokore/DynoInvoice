package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.application.MappingActivation
import com.dynokore.gst.rules.application.MappingPackDraft
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Properties
import java.util.UUID

private const val CONSTRAINT = "pack_registry_no_distinct_approver_activated"
private const val CHECK_VIOLATION = "23514"
private const val SET_FACT =
    "update rules.pack_registry set no_distinct_approver = ? where tenant_id = ?::uuid and pack_id = ? returning id"
private const val ROW = "select to_jsonb(p)::text from rules.pack_registry p where tenant_id = ?::uuid and pack_id = ?"
private const val COLUMN =
    "select no_distinct_approver from rules.pack_registry where tenant_id = ?::uuid and pack_id = ?"

/**
 * The activation fact of a mapping pack on real PostgreSQL (INV-T-19662, INV-AC-19661; INV-REQ-992, INV-REQ-995): the
 * store writes it with the activation and reads it back, a pack never activated reads false over a null column, and the
 * CHECK constraint refuses a value on a pack that is not activated. The database is the one PostgresRules builds from
 * every migration through MigrationRunner as rules_migrate; the statements of the constraint cases run as rules_migrate
 * under the signed tenant binding (the table forces row-level security on its owner too).
 */
@SpringBootTest(classes = [RulesApplication::class])
class MappingActivationPostgresIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired db: DSLContext,
) {
    private val migrate: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_migrate", "rules_migrate_pw")
    private val tenant = TenantId(UUID.randomUUID())
    private val store =
        JooqMappingPackStore(
            db,
            tenants,
            EngineVersion(BuildProperties(Properties().apply { setProperty("version", "1.0.0") })),
            Clock.systemUTC(),
        )

    private fun <T> asTenant(block: JooqMappingPackStore.() -> T): T =
        tenants.bind(tenant, "rules-actor") { tx.inTransaction { store.block() } }

    /** A draft of this test's tenant, moved on to [upTo] through the lifecycle the store supports. */
    private fun pack(upTo: PackStatus = PackStatus.DRAFT): String {
        val packId = "pack-" + UUID.randomUUID().toString().take(8)
        asTenant {
            draft(MappingPackDraft(packId, tenant, "E-1", packId, "rules-actor", PackStatus.DRAFT))
            if (upTo == PackStatus.APPROVED) {
                setStatus(packId, PackStatus.SUBMITTED)
                setStatus(packId, PackStatus.APPROVED)
            }
        }
        return packId
    }

    private fun activate(
        packId: String,
        noDistinctApprover: Boolean,
    ) = asTenant {
        recordActivation(
            packId,
            MappingActivation(LocalDate.parse("2026-05-01"), null, "rules-actor", Instant.now(), noDistinctApprover),
        )
    }

    private fun readBack(packId: String): Boolean? = asTenant { view(packId) }?.noDistinctApprover

    private fun column(packId: String): Any? =
        migrate.inTenant(tenant) { fetchValue(COLUMN, tenant.toString(), packId) }

    private fun row(packId: String): String =
        migrate.inTenant(tenant) { fetchValue(ROW, tenant.toString(), packId) } as String

    private fun setFact(
        packId: String,
        value: Boolean,
    ) = migrate.inTenant(tenant) { fetchValue(SET_FACT, value, tenant.toString(), packId) }

    private fun message(failure: Throwable): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    private fun sqlState(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    @Test
    fun `INV-AC-19661 an activation recorded with no distinct approver true reads back true`() {
        val packId = pack(PackStatus.APPROVED)
        activate(packId, true)
        column(packId) shouldBe true
        readBack(packId) shouldBe true
    }

    @Test
    fun `INV-AC-19661 an activation recorded with no distinct approver false reads back false`() {
        val packId = pack(PackStatus.APPROVED)
        activate(packId, false)
        column(packId) shouldBe false // stored as false, not null: the read is not the default of a null column
        readBack(packId) shouldBe false
    }

    @Test
    fun `INV-AC-19661 a pack never activated reads false while its column is null`() {
        val packId = pack()
        column(packId) shouldBe null
        readBack(packId) shouldBe false
    }

    @Test
    fun `INV-AC-19661 a value on a pack that is not activated is refused with 23514 naming the constraint`() {
        listOf(PackStatus.DRAFT, PackStatus.APPROVED).forEach { status ->
            val packId = pack(status)
            listOf(true, false).forEach { value ->
                val before = row(packId)
                val refused = shouldThrow<Throwable> { setFact(packId, value) }
                sqlState(refused) shouldBe CHECK_VIOLATION
                message(refused) shouldContain CONSTRAINT
                row(packId) shouldBe before
                column(packId) shouldBe null
            }
        }
    }

    @Test
    fun `INV-AC-19661 the same value on an ARMED row is accepted`() {
        val packId = pack(PackStatus.APPROVED)
        activate(packId, false)
        setFact(packId, true) shouldNotBe null // the update returned the row: accepted
        column(packId) shouldBe true
        readBack(packId) shouldBe true
        setFact(packId, false)
        column(packId) shouldBe false
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)

        private val pg get() = PostgresRules.pg
    }
}
