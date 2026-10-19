package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract test of the module API (done-when: "contract tests
 * pass"). `platform-contracts`/Pact (TPL-13, TPL-14) is for a service's HTTP
 * or event boundary; `evidence-store-api` is a Kotlin library boundary instead
 * — the same equivalence tenancy-kernel's own
 * `ModuleApiConsumerContractTest` KDoc gives: a fake consumer written the way
 * an evidence-svc use case would be (TPL-04, constructor-injected against
 * only [EvidenceLedger], [LegalHoldRegistry] and
 * [com.dynokore.platform.tenancykernel.TransactionRunner] — `-api` types
 * only), compiled and run against the real implementations. If this module's
 * impl ever drifts from what that consumer is written to expect — the chain,
 * the retention gate's interaction with a hold, an append-only refusal — this
 * fails, the job a consumer Pact does for an HTTP contract.
 */
@Tag("integration")
class ModuleApiConsumerContractTest {
    /** Written exactly as an evidence-svc use case would be (TPL-04): only `-api` types in its constructor. */
    private class FakeAuditPeriodCloseout(
        private val tx: TransactionRunner,
        private val ledger: EvidenceLedger,
        private val holds: LegalHoldRegistry,
    ) {
        fun recordAndExport(
            tenant: TenantId,
            entity: EntityRef,
            period: PeriodRef,
            contents: List<ByteArray>,
        ): List<EvidenceRecord> =
            tx.inTransaction {
                contents.forEach { ledger.append(tenant, entity, period, EvidenceType.PAYLOAD, it) }
                ledger.bulkExport(tenant, entity, period).toList()
            }

        fun verify(
            tenant: TenantId,
            entity: EntityRef,
            period: PeriodRef,
        ): ChainVerificationResult = tx.inTransaction { ledger.verifyChain(tenant, entity, period) }

        /** FR-D.4: a hold always refuses deletion, whatever [RetentionGate] alone would say. */
        fun canDeleteWithHold(
            chain: Triple<TenantId, EntityRef, PeriodRef>,
            retention: RetentionPeriod,
            periodEnd: LocalDate,
            asOf: LocalDate,
        ): Boolean =
            tx.inTransaction {
                val (tenant, entity, period) = chain
                RetentionGate.canDelete(retention, periodEnd, asOf, held = holds.isHeld(tenant, entity, period))
            }
    }

    @Test
    fun `records chain and export together, and a legal hold overrides an otherwise-elapsed retention period`() {
        val schema = EvidenceStorePostgresFixture.SCHEMA
        EvidenceStorePostgresFixture("postgres:18").use { fixture ->
            val tenants = TenantContextImpl()
            val pool = appPool(fixture)
            val tx = pool.runner(tenants, keys = fixture.keys)
            val dsl = dslOver(schema, tenants, tx)
            val ledger = JdbcEvidenceLedger(dsl, schema, EvidenceStorePostgresFixture.EVIDENCE_TABLE)
            val holds = JdbcLegalHoldRegistry(dsl, schema, EvidenceStorePostgresFixture.LEGAL_HOLD_TABLE)
            val useCase = FakeAuditPeriodCloseout(tx, ledger, holds)

            val tenant = TenantId(UUID.randomUUID())
            val entity = EntityRef("consumer-entity")
            // "2020-Q1": long elapsed, to exercise the retention side of the property below.
            val period = PeriodRef("2020-Q1")
            val periodEnd = LocalDate.of(2020, 3, 31)
            val contents = listOf("payload-a".toByteArray(), "payload-b".toByteArray())

            val exported =
                tenants.bind(
                    tenant,
                    "test-actor",
                ) { useCase.recordAndExport(tenant, entity, period, contents) }
            assertTrue(exported.map { String(it.content) } == contents.map { String(it) })

            val verified = tenants.bind(tenant, "test-actor") { useCase.verify(tenant, entity, period) }
            assertTrue(verified is ChainVerificationResult.Valid)

            // Retention alone (five years, statutory floor, FR-D.3) has long
            // elapsed by "now" — deletable.
            val retention = RetentionPeriod.DEFAULT
            val deletableWithoutHold =
                tenants.bind(tenant, "test-actor") {
                    useCase.canDeleteWithHold(Triple(tenant, entity, period), retention, periodEnd, LocalDate.now())
                }
            assertTrue(deletableWithoutHold)

            // A hold placed on the same (tenant, entity, period) refuses it regardless (FR-D.4).
            val hold = LegalHold("under dispute", Instant.now())
            tenants.bind(tenant, "test-actor") { tx.inTransaction { holds.place(tenant, entity, period, hold) } }
            val deletableWithHold =
                tenants.bind(tenant, "test-actor") {
                    useCase.canDeleteWithHold(Triple(tenant, entity, period), retention, periodEnd, LocalDate.now())
                }
            assertFalse(deletableWithHold)
            pool.close()
        }
    }

    private fun appPool(fixture: EvidenceStorePostgresFixture) =
        TestPoolFixture(
            fixture.jdbcUrl(),
            EvidenceStorePostgresFixture.APP_USER,
            EvidenceStorePostgresFixture.APP_PASSWORD,
            poolName = "evidence-contract",
        )

    private fun dslOver(
        schema: String,
        tenants: TenantContextImpl,
        tx: TransactionRunnerImpl,
    ) = TenancyJooq.dslContext(
        mapOf(
            DSL.name(schema, EvidenceStorePostgresFixture.EVIDENCE_TABLE) to TableClass.TENANT,
            DSL.name(schema, EvidenceStorePostgresFixture.LEGAL_HOLD_TABLE) to TableClass.TENANT,
        ),
        tenants,
        tx,
    )
}
