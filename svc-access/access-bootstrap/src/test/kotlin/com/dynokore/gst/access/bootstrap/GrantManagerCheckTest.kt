package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.LiveGrantManagerCheck
import com.dynokore.gst.access.adapters.http.LiveTenantGrants
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.adapters.http.TenantPolicyRoleResolver
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The caller check of grantRole, revokeGrant, recordSoleOperator and withdrawSoleOperatorAcknowledgement, and the roles
 * `setPolicy` asks (INV-T-4935, INV-Q-8167): the real beans over a real PostgreSQL, no allow-all binding anywhere. Each
 * of the four operations is called through its controller, the HTTP boundary of the service tests.
 */
class GrantManagerCheckTest {
    /** Own tenants per test: an allowed recordSoleOperator would otherwise leave an acknowledgement for later tests. */
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    /** One database serves every test, so each test names its people apart (the repeat key is unique per tenant). */
    private val suffix = UUID.randomUUID().toString().take(8)

    private fun id(name: String) = "$name-$suffix"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val grants = context.getBean(GrantsController::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)
        val live = context.getBean(LiveTenantGrants::class.java)
        val matrix = context.getBean(PermissionMatrix::class.java)

        fun seed(
            actor: String,
            role: Role,
            scope: Scope? = null,
            status: GrantStatus = GrantStatus.ACTIVE,
            window: ((Instant) -> ValidityWindow)? = null,
        ) = Seeds.grant(context, tenantA, SeedSpec(actor, role, scope, status, window))

        /** True when the operation was stopped by the caller check: 403 ACCESS_DENIED. */
        private fun denied(block: () -> Any?): Boolean =
            (runCatching { block() }.exceptionOrNull() as? ApiRefusal)?.code == ClosedErrorCode.ACCESS_DENIED

        /** Each of the four operations as [actor] in [tenant]: true for the ones the check refused. */
        fun refusals(
            actor: String,
            tenant: TenantId = tenantA,
        ): List<Boolean> =
            tenants.bind(tenant, actor) {
                listOf(
                    denied {
                        grants.grantRole(
                            UUID.randomUUID(),
                            GrantRequest(
                                GrantRequest.GranteeKind.STAFF,
                                id("IDN-NEW"),
                                "auditor",
                                "entity:ENT-1",
                                LocalDate.now().minusDays(1),
                                LocalDate.now().plusDays(10),
                            ),
                        )
                    },
                    denied { grants.revokeGrant("GRT-NONE", UUID.randomUUID()) },
                    denied {
                        soleOperator.recordSoleOperator(
                            UUID.randomUUID(),
                            SoleOperatorAckRequest(tenant.toString(), "reviewed by the external auditor"),
                        )
                    },
                    denied { soleOperator.withdrawSoleOperatorAcknowledgement("ACK-NONE", UUID.randomUUID()) },
                )
            }

        fun allowed(actor: String) = refusals(actor).none { it }

        fun refused(actor: String) = refusals(actor).all { it }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    @Test
    fun `an active tenant-scope tenant admin grant is allowed on all four operations`() =
        run { env ->
            env.seed(id("IDN-ADMIN"), Role.TENANT_ADMIN)
            assertThat(env.refusals(id("IDN-ADMIN"))).containsExactly(false, false, false, false)
        }

    @Test
    fun `no grant is refused on all four operations`() = run { env -> assertThat(env.refused(id("IDN-NONE"))).isTrue() }

    @Test
    fun `a revoked grant is refused`() =
        run { env ->
            env.seed(id("IDN-REV"), Role.TENANT_ADMIN, status = GrantStatus.REVOKED)
            assertThat(env.refused(id("IDN-REV"))).isTrue()
        }

    @Test
    fun `a window that ended before now or starts after now is refused`() =
        run { env ->
            env.seed(id("IDN-OLD"), Role.TENANT_ADMIN) { now ->
                ValidityWindow(now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(1)))
            }
            env.seed(id("IDN-SOON"), Role.TENANT_ADMIN) { now ->
                ValidityWindow(now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(9)))
            }
            assertThat(env.refused(id("IDN-OLD"))).isTrue()
            assertThat(env.refused(id("IDN-SOON"))).isTrue()
        }

    @Test
    fun `tenant-scope entity admin and security officer grants are refused, a role that differs only in its cells`() =
        run { env ->
            env.seed(id("IDN-EA"), Role.ENTITY_ADMIN)
            env.seed(id("IDN-SO"), Role.SECURITY_OFFICER)
            assertThat(env.refused(id("IDN-EA"))).isTrue()
            assertThat(env.refused(id("IDN-SO"))).isTrue()
        }

    @Test
    fun `a tenant admin grant of group or entity scope is refused`() =
        run { env ->
            env.seed(id("IDN-GRP"), Role.TENANT_ADMIN, Scope.Group(GroupRef("GRP-1"), TenantRef(tenantA.toString())))
            env.seed(id("IDN-ENT"), Role.TENANT_ADMIN, Scope.Entity(EntityRef("ENT-1")))
            assertThat(env.refused(id("IDN-GRP"))).isTrue()
            assertThat(env.refused(id("IDN-ENT"))).isTrue()
        }

    @Test
    fun `a tenant admin grant in tenant B does not count for a request bound to tenant A`() =
        run { env ->
            Seeds.grant(env.context, tenantB, SeedSpec(id("IDN-B"), Role.TENANT_ADMIN))
            assertThat(env.refusals(id("IDN-B"), tenantA).all { it }).isTrue()
            assertThat(env.refusals(id("IDN-B"), tenantB).none { it }).isTrue()
        }

    @Test
    fun `a tenant-scope grant of another tenant stored under this tenant does not count`() =
        run { env ->
            env.seed(id("IDN-X"), Role.TENANT_ADMIN, Scope.Tenant(TenantRef(tenantB.toString())))
            assertThat(env.refused(id("IDN-X"))).isTrue()
        }

    private fun check(
        env: Env,
        matrix: PermissionMatrix,
        context: TenantContext = env.tenants,
        live: LiveTenantGrants = env.live,
    ): GrantManagerCheck = LiveGrantManagerCheck(context, live, matrix)

    private fun matrixWith(cells: Map<com.dynokore.gst.access.domain.role.Permission, Allowance>) =
        PermissionMatrix(
            listOf(
                PermissionDefinition(FixtureMatrix.USER_MANAGE, PermissionKind.WRITE),
                PermissionDefinition(FixtureMatrix.VIEW, PermissionKind.READ),
            ),
            mapOf(Role.TENANT_ADMIN to cells),
        )

    @Test
    fun `the matrix decides, not the role name`() =
        run { env ->
            env.seed(id("IDN-ADMIN"), Role.TENANT_ADMIN)

            fun answer(matrix: PermissionMatrix) =
                env.tenants.bind(tenantA, id("IDN-ADMIN")) { check(env, matrix).mayManageGrants() }
            assertThat(answer(env.matrix)).isTrue()
            assertThat(answer(matrixWith(mapOf(FixtureMatrix.USER_MANAGE to Allowance.PERMITTED)))).isTrue()
            assertThat(answer(matrixWith(mapOf(FixtureMatrix.USER_MANAGE to Allowance.WITHIN_SCOPE)))).isFalse()
            assertThat(answer(matrixWith(mapOf(FixtureMatrix.VIEW to Allowance.PERMITTED)))).isFalse()
        }

    private fun unboundContext(
        actor: String?,
        tenant: TenantId,
    ) = object : TenantContext {
        override fun current() = tenant

        override fun currentActor() = actor

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }

    @Test
    fun `no actor bound, an invalid actor and the platform tenant are refused`() =
        run { env ->
            env.seed(id("IDN-ADMIN"), Role.TENANT_ADMIN)
            assertThat(check(env, env.matrix).mayManageGrants()).isFalse()
            assertThat(check(env, env.matrix, unboundContext(null, tenantA)).mayManageGrants()).isFalse()
            assertThat(check(env, env.matrix, unboundContext(" ", tenantA)).mayManageGrants()).isFalse()
            assertThat(check(env, env.matrix, unboundContext(id("IDN-ADMIN"), TenantId.PLATFORM)).mayManageGrants())
                .isFalse()
        }

    @Test
    fun `a repository that throws never allows`() =
        run { env ->
            val failing =
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GrantRepository::class.java)) { _, _, _ ->
                    error("database down")
                } as GrantRepository
            val live =
                LiveTenantGrants(
                    env.tenants,
                    env.context.getBean(TransactionRunner::class.java),
                    failing,
                    Clock.systemUTC(),
                )
            val roles = TenantPolicyRoleResolver(live)
            env.tenants.bind(tenantA, id("IDN-ADMIN")) {
                assertThat(check(env, env.matrix, live = live).mayManageGrants()).isFalse()
            }
            assertThat(roles.rolesOf(id("IDN-ADMIN"), tenantA, ScopeLevel.TENANT, tenantA.toString(), null)).isEmpty()
        }

    @Test
    fun `after the caller revokes their own tenant admin grant a second call is refused`() =
        run { env ->
            val own = env.seed(id("IDN-SELF"), Role.TENANT_ADMIN)
            assertThat(env.allowed(id("IDN-SELF"))).isTrue()
            env.tenants.bind(tenantA, id("IDN-SELF")) { env.grants.revokeGrant(own, UUID.randomUUID()) }
            assertThat(env.refused(id("IDN-SELF"))).isTrue()
        }

    @Test
    fun `the service context binds the live check and the live resolver, never the deny-all`() =
        run { env ->
            val bound = env.context.getBean(GrantManagerCheck::class.java)
            assertThat(bound).isNotSameAs(GrantManagerCheck.DENY_ALL).isInstanceOf(LiveGrantManagerCheck::class.java)
            assertThat(env.context.getBean(PolicyRoleResolver::class.java))
                .isInstanceOf(TenantPolicyRoleResolver::class.java)
            assertThat(env.refused(id("IDN-NOBODY"))).isTrue()
        }

    @Test
    fun `policy roles - tenant admin gets exactly tenant admin`() =
        run { env ->
            env.seed(id("IDN-ADMIN"), Role.TENANT_ADMIN)
            assertThat(
                roles(env, id("IDN-ADMIN"), ScopeLevel.TENANT, tenantA.toString()),
            ).containsExactly("tenant.admin")
        }

    @Test
    fun `policy roles - group scope, other level, other scope reference and revoked grant give the empty set`() =
        run { env ->
            env.seed(id("IDN-GRP"), Role.TENANT_ADMIN, Scope.Group(GroupRef("GRP-1"), TenantRef(tenantA.toString())))
            env.seed(id("IDN-ADMIN"), Role.TENANT_ADMIN)
            env.seed(id("IDN-REV"), Role.TENANT_ADMIN, status = GrantStatus.REVOKED)
            assertThat(roles(env, id("IDN-GRP"), ScopeLevel.TENANT, tenantA.toString())).isEmpty()
            assertThat(roles(env, id("IDN-ADMIN"), ScopeLevel.ENTITY, tenantA.toString())).isEmpty()
            assertThat(roles(env, id("IDN-ADMIN"), ScopeLevel.TENANT, tenantB.toString())).isEmpty()
            assertThat(roles(env, id("IDN-ADMIN"), ScopeLevel.TENANT, null)).isEmpty()
            assertThat(roles(env, id("IDN-REV"), ScopeLevel.TENANT, tenantA.toString())).isEmpty()
            assertThat(roles(env, id("IDN-ADMIN"), ScopeLevel.TENANT, tenantA.toString().uppercase())).isEmpty()
        }

    private fun roles(
        env: Env,
        actor: String,
        level: ScopeLevel,
        scopeRef: String?,
    ): Set<String> = env.context.getBean(PolicyRoleResolver::class.java).rolesOf(actor, tenantA, level, scopeRef, null)
}
