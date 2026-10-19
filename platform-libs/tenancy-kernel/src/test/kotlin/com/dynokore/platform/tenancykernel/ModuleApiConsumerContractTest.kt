package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Consumer-driven contract test of the module API (done-when). The two statement tests (nested propagation, atomicity)
 * moved to tenancy-jooq's `ModuleApiConsumerTransactionContractTest` (coordinator ruling INV-Q-5208): the kernel can
 * build no DSLContext. The licence port's non-blocking guarantee stays here, with no statement.
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
        private val licence: LicenceCheck,
    ) {
        fun licenceStatusNeverBlocks(): LicenceStatus = licence.status(tenants.current())
    }

    @Test
    fun `LicenceCheck never blocks the consumer, even wired to a hanging delegate (FR-G_12)`() {
        PostgresProbeFixture("postgres:18").use { fixture ->
            val app = PostgresProbeFixture.APP
            TestPoolFixture(fixture.jdbcUrl(), app, PostgresProbeFixture.APP_PASSWORD).use { pool ->
                val tenants = TenantContextImpl()
                pool.runner(tenants, keys = SignedBinding.keys())
                val hangingLicence =
                    NonBlockingLicenceCheck(
                        delegate =
                            LicenceCheck {
                                Thread.sleep(10_000)
                                LicenceStatus.EXPIRED
                            },
                        timeout = Duration.ofMillis(150),
                    )
                val useCase = FakeConsumerUseCase(tenants, hangingLicence)
                val tenant = TenantId(UUID.randomUUID())

                assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                    val status = tenants.bind(tenant, "test-actor") { useCase.licenceStatusNeverBlocks() }
                    assertEquals(LicenceStatus.UNKNOWN, status)
                }
            }
        }
    }
}
