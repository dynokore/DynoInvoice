package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ACC-001 wiring (INV-T-23857; INV-AC-23854, INV-AC-23855): the real use cases, built by the composition root,
 * write the
 * alert in the outbox. A bean left on `OrphanedApproverCheck.NotWired` makes these tests fail.
 */
class OrphanedApproverWiringTest {
    private fun setup(
        context: org.springframework.boot.test.context.assertj.AssertableWebApplicationContext,
        tenant: TenantId,
    ) {
        OrphanEnv.entity(context, tenant, "E1")
        Seeds.grant(context, tenant, SeedSpec("user:admin", Role.TENANT_ADMIN))
    }

    @Test
    fun `INV-AC-23854 INV-AC-23855 revoking the only approver through RevokeGrantUseCase writes one ACC-001 alert`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val tenant = TenantId(UUID.randomUUID())
            setup(context, tenant)
            val e1 = Scope.Entity(EntityRef("E1"))
            val approver = Seeds.grant(context, tenant, SeedSpec("user:ap", Role.TAX_APPROVER, e1))
            val operator = Seeds.grant(context, tenant, SeedSpec("user:op", Role.TAX_OPERATOR, e1))
            val revoke = context.getBean(RevokeGrantUseCase::class.java)
            val tenants = context.getBean(TenantContext::class.java)

            tenants.bind(tenant, "user:admin") { revoke.handle("user:admin", GrantRef(operator)) }
            assertThat(OrphanEnv.alerts(tenant)).isEmpty()

            tenants.bind(tenant, "user:admin") { revoke.handle("user:admin", GrantRef(approver)) }
            val alerts = OrphanEnv.alerts(tenant)
            assertThat(alerts).hasSize(1)
            assertThat(alerts.single()).contains("ACC-001").contains("entity:E1")
        }
    }

    @Test
    fun `INV-AC-23854 INV-AC-23855 a directory-group reconciliation removing the only approver writes one alert`() {
        val members = HashMap<String, Set<IdentityRef>>()
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { DirectoryGroupMembers { g -> members[g].orEmpty() } })
            .run { context ->
                context.startupFailure?.let { throw it }
                val tenant = TenantId(UUID.randomUUID())
                setup(context, tenant)
                val tenants = context.getBean(TenantContext::class.java)
                members["grp-ap"] = setOf(IdentityRef("IDN-AP"))
                tenants.bind(tenant, "user:admin") {
                    context
                        .getBean(IdpGroupController::class.java)
                        .mapIdpGroup(
                            UUID.randomUUID(),
                            IdpGroupMappingRequest("grp-ap", "tax.approver", "entity:E1", null, null),
                        )
                }
                assertThat(OrphanEnv.alerts(tenant)).isEmpty()
                members["grp-ap"] = emptySet()
                tenants.bind(tenant, "user:admin") {
                    context.getBean(SyncIdpGroupsUseCase::class.java).run("user:admin", TenantRef(tenant.toString()))
                }
                val alerts = OrphanEnv.alerts(tenant)
                assertThat(alerts).hasSize(1)
                assertThat(alerts.single()).contains("ACC-001").contains("entity:E1")
            }
    }
}
