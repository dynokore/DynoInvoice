package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Consumer-driven contract test of the module API (done-when).
 *
 * `platform-contracts`/Pact (TPL-13, TPL-14) is for a service's HTTP or event
 * boundary; `tenancy-kernel-api` is a Kotlin library boundary instead, so its
 * equivalent is this: a fake consumer written the way TPL-04's `RecordFilingFact`
 * is (constructor-injected against [TenantContext], [TransactionRunner] and
 * [LicenceCheck] — the exact three ports TPL-01/TPL-04 name as
 * `tenancy-kernel-api`), compiled and run against the *real* implementation. If
 * this module's impl ever drifts from the behaviour `application`-layer code is
 * written to expect — propagation, atomicity, current()'s contract, the
 * licence port's non-blocking guarantee — this fails, the same job a consumer
 * Pact does for an HTTP contract.
 */
@Tag("integration")
class ModuleApiConsumerContractTest {
    /** Written exactly as `submission-application`'s use cases are (TPL-04): only `-api` types in its constructor. */
    private class FakeConsumerUseCase(
        private val tenants: TenantContext,
        private val tx: TransactionRunner,
        private val licence: LicenceCheck,
        private val connections: CurrentConnection,
    ) {
        fun recordTwoProbeRowsInOneTransaction(
            first: ProbeRow,
            second: ProbeRow,
        ) {
            tx.inTransaction {
                ProbeTable.insert(connections.require(), first)
                tx.inTransaction { ProbeTable.insert(connections.require(), second) } // nested: REQUIRED propagation
            }
        }

        fun recordThenFailMidway(
            first: ProbeRow,
            second: ProbeRow,
        ) {
            tx.inTransaction {
                ProbeTable.insert(connections.require(), first)
                tx.inTransaction {
                    ProbeTable.insert(connections.require(), second)
                    error("simulated failure after both writes, before commit")
                }
            }
        }

        fun currentTenant(): TenantId = tenants.current()

        fun licenceStatusNeverBlocks(): LicenceStatus = licence.status(tenants.current())
    }

    @Test
    fun `nested inTransaction joins one transaction (REQUIRED propagation) and current() reflects the bound tenant`() {
        PostgresProbeFixture("postgres:18").use { fixture ->
            fixture.createProbeTable(fixture.ownerDataSource())
            val tenants = TenantContextImpl()
            val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
            val alwaysOk = NonBlockingLicenceCheck(LicenceCheck { LicenceStatus.OK })
            val useCase = FakeConsumerUseCase(tenants, tx, alwaysOk, tx)
            val tenant = TenantId(UUID.randomUUID())

            val seenTenant = tenants.bind(tenant) { useCase.currentTenant() }
            assertEquals(tenant, seenTenant)

            tenants.bind(tenant) {
                useCase.recordTwoProbeRowsInOneTransaction(
                    ProbeRow(UUID.randomUUID(), tenant, "nested-first"),
                    ProbeRow(UUID.randomUUID(), tenant, "nested-second"),
                )
            }

            val rows =
                tenants.bind(tenant) { tx.inTransaction { ProbeTable.selectAll(tx.require()) } }
                    .map { it.value }
            val expected = listOf("nested-first", "nested-second")
            assertTrue(rows.containsAll(expected), "both nested writes must be visible: $rows")
        }
    }

    @Test
    fun `a failure anywhere in the nested transaction rolls back every write in it (ARC-05 atomicity)`() {
        PostgresProbeFixture("postgres:18").use { fixture ->
            fixture.createProbeTable(fixture.ownerDataSource())
            val tenants = TenantContextImpl()
            val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
            val alwaysOk = NonBlockingLicenceCheck(LicenceCheck { LicenceStatus.OK })
            val useCase = FakeConsumerUseCase(tenants, tx, alwaysOk, tx)
            val tenant = TenantId(UUID.randomUUID())

            assertThrows(IllegalStateException::class.java) {
                tenants.bind(tenant) {
                    useCase.recordThenFailMidway(
                        ProbeRow(UUID.randomUUID(), tenant, "rolled-back-first"),
                        ProbeRow(UUID.randomUUID(), tenant, "rolled-back-second"),
                    )
                }
            }

            val rows =
                tenants.bind(tenant) { tx.inTransaction { ProbeTable.selectAll(tx.require()) } }
                    .map { it.value }
            assertTrue(rows.none { it.startsWith("rolled-back") }, "neither write must survive the rollback: $rows")
        }
    }

    @Test
    fun `LicenceCheck never blocks the consumer, even wired to a hanging delegate (FR-G_12)`() {
        PostgresProbeFixture("postgres:18").use { fixture ->
            fixture.createProbeTable(fixture.ownerDataSource())
            val tenants = TenantContextImpl()
            val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
            val hangingLicence =
                NonBlockingLicenceCheck(
                    delegate =
                    LicenceCheck {
                        Thread.sleep(10_000)
                        LicenceStatus.EXPIRED
                    },
                    timeout = Duration.ofMillis(150),
                )
            val useCase = FakeConsumerUseCase(tenants, tx, hangingLicence, tx)
            val tenant = TenantId(UUID.randomUUID())

            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                val status = tenants.bind(tenant) { useCase.licenceStatusNeverBlocks() }
                assertEquals(LicenceStatus.UNKNOWN, status)
            }
        }
    }
}
