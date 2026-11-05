package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * The parts of INV-T-4936 that the published access contract carries (INV-REQ-860, INV-REQ-993): the permission
 * matrix gives mapping-pack approval to `tax.approver` and override-policy administration to `tenant.admin`, the
 * external advisor's grant is made by `tenant.admin` and expires, and one person is not given `tax.approver` and
 * `tenant.admin` without the recorded sole-operator acknowledgement. Real application context over a real PostgreSQL,
 * the controllers called under a bound tenant, as in [CatalogueAcceptanceTest].
 */
class ApprovalSeparationTest {
    private val admin = "user:tenant-admin"

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val grants = context.getBean(GrantsController::class.java)
        val authorise = context.getBean(AuthoriseController::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(
                        tenant,
                        "ENT-1",
                        StructureChange.CREATED,
                        BoundaryFixture.entityNode(),
                    ),
                )
        }

        fun <T> asUser(
            identity: String,
            block: () -> T,
        ): T = tenants.bind(tenant, identity) { block() }

        @Suppress("LongParameterList") // test helper mirroring the grantRole request
        fun grant(
            caller: String,
            identity: String,
            role: String,
            scope: String,
            kind: GrantRequest.GranteeKind = GrantRequest.GranteeKind.STAFF,
            validTo: LocalDate = LocalDate.now().plusDays(400),
        ) = asUser(caller) {
            grants.grantRole(
                UUID.randomUUID(),
                GrantRequest(kind, identity, role, scope, LocalDate.now().minusDays(1), validTo),
            )
        }

        fun decide(
            identity: String,
            permission: String,
        ): Decision = asUser(admin) { authorise.authorise(identity, permission, "entity:ENT-1").body!!.decision }

        fun refusal(block: () -> Any?): ApiRefusal {
            val thrown = runCatching { block() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
            return thrown as ApiRefusal
        }

        fun acknowledge() =
            asUser(admin) {
                soleOperator.recordSoleOperator(
                    UUID.randomUUID(),
                    SoleOperatorAckRequest(tenant.toString(), "external review of every approval"),
                )
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context, TenantId(UUID.randomUUID())))
        }
    }

    @Test
    fun `INV-AC-9552 approval of a mapping pack is tax approver only and override policy is tenant admin only`() =
        run { env ->
            env.grant(admin, "IDN-U1", "tenant.admin", "entity:ENT-1")
            env.grant(admin, "IDN-U2", "tax.approver", "entity:ENT-1")
            // the pack's author (U3) is not part of the contract's authorise question; the permission is what is read
            val decisions =
                listOf(
                    env.decide("IDN-U1", "mapping-pack.approve"),
                    env.decide("IDN-U2", "mapping-pack.approve"),
                    env.decide("IDN-U2", "override-policy.manage"),
                    env.decide("IDN-U1", "override-policy.manage"),
                )
            assertThat(decisions).containsExactly(Decision.DENY, Decision.ALLOW, Decision.DENY, Decision.ALLOW)
        }

    @Test
    fun `INV-AC-9554 the advisor grant is made by tenant admin only and stops approving when it expires`() =
        run { env ->
            env.grant(admin, "IDN-C1", "tax.approver", "entity:ENT-1")
            val validTo = LocalDate.now().plusDays(60)
            val advisor = GrantRequest.GranteeKind.EXTERNAL
            val byApprover =
                env.refusal { env.grant("IDN-C1", "IDN-X", "tax.approver", "entity:ENT-1", advisor, validTo) }
            assertThat(byApprover.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            val byAdmin = env.grant(admin, "IDN-X", "tax.approver", "entity:ENT-1", advisor, validTo)
            assertThat(byAdmin.statusCode.value()).isEqualTo(201)
            val before = validTo.minusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
            env.clock.advance(Duration.between(env.clock.instant(), before.plusSeconds(43_200)))
            val onTheLastDay = env.decide("IDN-X", "mapping-pack.approve")
            env.clock.advance(Duration.ofDays(2))
            val afterwards = env.decide("IDN-X", "mapping-pack.approve")
            assertThat(listOf(onTheLastDay, afterwards)).containsExactly(Decision.ALLOW, Decision.DENY)
        }

    @Test
    fun `INV-AC-2042 tenant admin is refused to a tax approver without the acknowledgement and granted with it`() =
        run { env ->
            env.grant(admin, "IDN-U", "tax.approver", "entity:ENT-1")
            val scope = "tenant:${env.tenant}"
            val refused = env.refusal { env.grant(admin, "IDN-U", "tenant.admin", scope) }
            assertThat(refused.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(refused.detail).contains("tax.approver and tenant.admin")
            assertThat(env.acknowledge().statusCode.value()).isEqualTo(201)
            assertThat(env.grant(admin, "IDN-U", "tenant.admin", scope).statusCode.value()).isEqualTo(201)
        }

    @Test
    fun `INV-AC-2042 the other order is refused too and a look-alike role is not in conflict`() =
        run { env ->
            val scope = "tenant:${env.tenant}"
            env.grant(admin, "IDN-V", "tenant.admin", scope)
            assertThat(env.refusal { env.grant(admin, "IDN-V", "tax.approver", "entity:ENT-1") }.code)
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.grant(admin, "IDN-V", "tax.operator", "entity:ENT-1").statusCode.value()).isEqualTo(201)
        }
}
