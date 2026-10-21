package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-AC-8288, cases C1 to C9: under the default value `ONE_HOLDER_WITHDRAW_BLOCKED` of VP-81 (INV-REQ-8287) one person
 * holds both sole-operator roles, and the acknowledgement cannot be withdrawn while anyone holds both. Real application
 * context over a real PostgreSQL, the controllers called under a bound tenant, as in [CatalogueAcceptanceTest].
 */
class SoleOperatorScopeTest {
    // every test has its own tenants: the database outlives a test, and holders are counted per tenant
    private fun freshTenant() = TenantId(UUID.randomUUID())

    private val admin = "user:tenant-admin"
    private val d1 =
        "only one person may hold security.officer and tenant.admin under the sole-operator acknowledgement"
    private val d2 = "a person still holds security.officer and tenant.admin; revoke one of the roles first"
    private val d3 =
        "a second person must hold security.officer or tenant.admin before the acknowledgement is withdrawn"

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val grants = context.getBean(GrantsController::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
        }

        fun <T> asAdmin(block: () -> T): T = tenants.bind(tenant, admin) { block() }

        fun grant(
            identity: String,
            role: String,
        ) = asAdmin {
            grants.grantRole(
                UUID.randomUUID(),
                GrantRequest(
                    GrantRequest.GranteeKind.STAFF,
                    identity,
                    role,
                    "tenant:$tenant",
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(400),
                ),
            )
        }

        fun record(): String =
            asAdmin {
                soleOperator
                    .recordSoleOperator(UUID.randomUUID(), SoleOperatorAckRequest(tenant.toString(), "external audit"))
                    .body!!
                    .ackRef
            }

        fun withdraw(ackRef: String) =
            asAdmin { soleOperator.withdrawSoleOperatorAcknowledgement(ackRef, UUID.randomUUID()) }

        private val acks = context.getBean(SoleOperatorAcks::class.java)

        fun activeAck() = tenants.bind(tenant, admin) { tx.inTransaction { acks.findActive() } }

        /** The people who hold [role] in this tenant (a grant that is not revoked). */
        fun holders(role: Role): Set<String> =
            tenants.bind(tenant, admin) { tx.inTransaction { acks.holders(role).map { it.value }.toSet() } }

        fun refusal(block: () -> Any?): ApiRefusal {
            val thrown = runCatching { block() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
            return thrown as ApiRefusal
        }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, form = "SAAS").run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context, freshTenant()))
        }
    }

    private fun Env.assertRefused(
        refusal: ApiRefusal,
        detail: String,
    ) {
        assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(refusal.detail).isEqualTo(detail)
    }

    @Test
    fun `INV-AC-8288 C1 the first holder is granted both roles under the acknowledgement`() =
        run { env ->
            env.grant("IDN-P1", "tenant.admin")
            env.record()
            assertThat(env.grant("IDN-P1", "security.officer").statusCode.value()).isEqualTo(201)
            assertThat(env.holders(Role.SECURITY_OFFICER)).contains("IDN-P1")
        }

    @Test
    fun `INV-AC-8288 C2 a second holder is refused with D1 and no grant is written`() =
        run { env ->
            env.grant("IDN-P1", "tenant.admin")
            env.record()
            env.grant("IDN-P1", "security.officer")
            env.grant("IDN-P2", "tenant.admin")
            val refusal = env.refusal { env.grant("IDN-P2", "security.officer") }
            env.assertRefused(refusal, d1)
            assertThat(env.holders(Role.SECURITY_OFFICER)).containsOnly("IDN-P1")
            // the other order is refused too
            env.grant("IDN-P3", "security.officer")
            env.assertRefused(env.refusal { env.grant("IDN-P3", "tenant.admin") }, d1)
            assertThat(env.holders(Role.TENANT_ADMIN)).doesNotContain("IDN-P3")
        }

    @Test
    fun `INV-AC-8288 C3 a look-alike role is granted while another person holds both`() =
        run { env ->
            env.grant("IDN-P1", "tenant.admin")
            env.record()
            env.grant("IDN-P1", "security.officer")
            env.grant("IDN-P2", "tenant.admin")
            assertThat(env.grant("IDN-P2", "entity.admin").statusCode.value()).isEqualTo(201)
        }

    @Test
    fun `INV-AC-8288 C4 a holder in another tenant does not count`() =
        BoundaryFixture.runner(catalogueMatrix = true, form = "SAAS").run { context ->
            context.startupFailure?.let { throw it }
            val b = Env(context, freshTenant())
            b.grant("IDN-P1", "tenant.admin")
            b.record()
            b.grant("IDN-P1", "security.officer")
            val a = Env(context, freshTenant())
            a.grant("IDN-P2", "tenant.admin")
            a.record()
            assertThat(a.grant("IDN-P2", "security.officer").statusCode.value()).isEqualTo(201)
            assertThat(a.holders(Role.SECURITY_OFFICER)).containsOnly("IDN-P2")
        }

    @Test
    fun `INV-AC-8288 C5 a revoked grant does not count`() =
        run { env ->
            Seeds.grant(env.context, env.tenant, SeedSpec("IDN-P1", Role.TENANT_ADMIN))
            val revoked = SeedSpec("IDN-P1", Role.SECURITY_OFFICER, status = GrantStatus.REVOKED)
            Seeds.grant(env.context, env.tenant, revoked)
            env.record()
            env.grant("IDN-P2", "tenant.admin")
            assertThat(env.grant("IDN-P2", "security.officer").statusCode.value()).isEqualTo(201)
        }

    @Test
    fun `INV-AC-8288 C6 withdrawal while a person holds both is refused with D2 and nothing changes`() =
        run { env ->
            env.grant("IDN-P1", "tenant.admin")
            val ack = env.record()
            env.grant("IDN-P1", "security.officer")
            val officers = env.holders(Role.SECURITY_OFFICER)
            val admins = env.holders(Role.TENANT_ADMIN)
            env.assertRefused(env.refusal { env.withdraw(ack) }, d2)
            assertThat(env.activeAck()?.ref).isEqualTo(ack)
            assertThat(env.holders(Role.SECURITY_OFFICER)).isEqualTo(officers)
            assertThat(env.holders(Role.TENANT_ADMIN)).isEqualTo(admins)
        }

    @Test
    fun `INV-AC-8288 C7 withdrawal with one person only is refused with D3 before D2`() =
        run { env ->
            val ack = env.record()
            env.grant(admin, "security.officer")
            // one person holds both: D3 (only one person) comes before D2
            env.assertRefused(env.refusal { env.withdraw(ack) }, d3)
            assertThat(env.activeAck()?.ref).isEqualTo(ack)
        }

    @Test
    fun `INV-AC-8288 C8 withdrawal with nobody holding both and two people holding one role each answers 204`() =
        run { env ->
            env.grant("IDN-P1", "security.officer")
            val ack = env.record()
            val before = env.holders(Role.SECURITY_OFFICER) to env.holders(Role.TENANT_ADMIN)
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(env.activeAck()).isNull()
            assertThat(env.holders(Role.SECURITY_OFFICER) to env.holders(Role.TENANT_ADMIN)).isEqualTo(before)
            // the pair is refused again afterwards
            val refusal = env.refusal { env.grant("IDN-P1", "tenant.admin") }
            assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refusal.detail)
                .isEqualTo("security.officer and tenant.admin need the recorded sole-operator acknowledgement")
        }

    @Test
    fun `INV-AC-8288 C9 two simultaneous requests for different people give one 201 and one D1`() =
        run { env ->
            env.grant("IDN-P1", "tenant.admin")
            env.grant("IDN-P2", "tenant.admin")
            env.record()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf("IDN-P1", "IDN-P2").map { person ->
                        pool.submit<Result<Int>> {
                            start.await()
                            runCatching { env.grant(person, "security.officer").statusCode.value() }
                        }
                    }
                start.countDown()
                val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                val granted = results.mapNotNull { it.getOrNull() }
                val refused = results.mapNotNull { it.exceptionOrNull() as? ApiRefusal }
                assertThat(granted).containsExactly(201)
                assertThat(refused).hasSize(1)
                env.assertRefused(refused.single(), d1)
                assertThat(env.holders(Role.SECURITY_OFFICER)).hasSize(1)
            } finally {
                pool.shutdownNow()
            }
        }
}
