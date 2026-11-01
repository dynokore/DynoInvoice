package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.FIRST
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLATION
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.OTHER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.expectedCode
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provisionBody
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.seedAdminRow
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.USER_A_TOKEN
import com.dynokore.gst.access.bootstrap.served.USER_B_TOKEN
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** INV-Q-14264 A: the prefix `invitation:` is refused as an identity of grantRole, exactly and case-sensitively. */
class ReservedInvitationIdentityTest {
    private fun grantRole(
        identity: String,
        block: (Result<Int>, TenantId) -> Unit,
    ) = withService { served ->
        val tenant = newTenant()
        val context = served.application
        Seeds.grant(context, tenant, SeedSpec("IDN-MGR", Role.TENANT_ADMIN))
        val api = context.getBean(com.dynokore.gst.access.adapters.http.GrantsController::class.java)
        val result =
            runCatching {
                context.getBean(TenantContext::class.java).bind(tenant, "IDN-MGR") {
                    api
                        .grantRole(
                            UUID.randomUUID(),
                            com.dynokore.gst.access.contract.model.GrantRequest(
                                com.dynokore.gst.access.contract.model.GrantRequest.GranteeKind.STAFF,
                                identity,
                                "auditor",
                                "tenant:$tenant",
                                java.time.LocalDate
                                    .now()
                                    .minusDays(1),
                                java.time.LocalDate
                                    .now()
                                    .plusDays(10),
                            ),
                        ).statusCode
                        .value()
                }
            }
        block(result, tenant)
    }

    @Test
    fun `INV-AC-8224 grantRole with the reserved prefix is 400 and stores nothing`() =
        grantRole("invitation:INVN-X") { result, tenant ->
            val failure = result.exceptionOrNull() as com.dynokore.gst.access.adapters.http.ApiRefusal
            assertThat(failure.code.httpStatus).isEqualTo(400)
            assertThat(failure.detail).isEqualTo("identityRef is invalid")
            assertThat(rows("access.identity", tenant).filter { it.contains("INVN-X") }).isEmpty()
            assertThat(rows("access.role_grant", tenant).filter { it.contains("INVN-X") }).isEmpty()
        }

    @Test
    fun `INV-AC-8224 the look-alike prefixes are not refused by the prefix rule`() {
        listOf("invitations:INVN-X", "Invitation:INVN-X", " invitation:INVN-X").forEach { identity ->
            grantRole(identity) { result, _ -> assertThat(result.getOrNull()).describedAs(identity).isEqualTo(201) }
        }
    }
}
