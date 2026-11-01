package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.FirstAdministratorProvisioning
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministrator
import com.dynokore.gst.access.application.invitation.ReissueFirstAdministratorInvitation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLATION
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.LOOK_ALIKE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.TABLES
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.first
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN
import com.dynokore.gst.access.contract.model.FirstAdministratorRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.OffsetDateTime
import java.util.UUID

private val ACTOR_TABLES =
    listOf("access.role_grant", "access.identity", "access.first_administrator_invitation")

/**
 * INV-AC-8224: the actor of a call (INV-Q-14500 B): the `sub` of a bound end-user principal, otherwise `svc:` + the
 * verified calling workload; never the service's own `svc:access-svc`.
 */
class FirstAdministratorActorTest {
    private fun actorsOf(tenant: TenantId): Set<String> =
        ACTOR_TABLES
            .flatMap { table -> rows(table, tenant).map { json(it) } }
            .flatMap { listOf(it["created_by"].asString(), it["updated_by"].asString()) }
            .toSet()

    private fun auditOf(served: com.dynokore.gst.access.bootstrap.served.ServedContext): List<String> =
        served.application
            .getBean(CapturedEvents::class.java)
            .envelopes
            .filter { it.type.contains("AuditEntryRecorded") && it.dataJson.contains("FirstAdministrator") }
            .map { it.dataJson }

    @Test
    fun `INV-AC-8224 an installer call stores svc-installer in all four places and no svc-access-svc`() =
        withService { served ->
            val tenant = newTenant()
            val response = served.provision(INSTALLATION, INSTALLER, tenant.toString(), auth = null)
            assertThat(response.status).isEqualTo(201)
            ACTOR_TABLES.forEach { assertThat(rows(it, tenant)).describedAs(it).isNotEmpty }
            assertThat(actorsOf(tenant)).containsExactly("svc:installer")
            val audit = auditOf(served)
            assertThat(audit).isNotEmpty
            assertThat(audit.last()).contains("svc:installer").doesNotContain("svc:access-svc")
        }

    @Test
    fun `INV-AC-8224 a control-plane call with an end-user token stores the sub and not svc-control-plane-svc`() =
        withService { served ->
            val tenant = newTenant()
            assertThat(served.first(tenant, auth = OPERATOR_TOKEN).status).isEqualTo(201)
            assertThat(actorsOf(tenant)).containsExactly("operator-1")
            assertThat(auditOf(served).last()).contains("operator-1").doesNotContain("svc:control-plane-svc")
        }

    @Test
    fun `INV-AC-8224 the controller refuses a null verified caller with 403 ACCESS_DENIED and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val before = snapshot(tenant)
            val adapter =
                FirstAdministratorProvisioning(
                    served.application.getBean(ProvisionFirstAdministrator::class.java),
                    served.application.getBean(ReissueFirstAdministratorInvitation::class.java),
                    served.tenants,
                    MockHttpServletRequest(),
                    object : ServiceCaller {
                        override fun current(): String? = null
                    },
                )
            val body =
                FirstAdministratorRequest(
                    tenantRef = tenant.toString(),
                    administratorEmail = FirstAdministratorFixture.EMAIL,
                    invitationExpiresAt = OffsetDateTime.parse(FirstAdministratorFixture.EXPIRES),
                )
            assertThatThrownBy { adapter.provision(UUID.randomUUID(), body) }
                .isInstanceOfSatisfying(ApiRefusal::class.java) {
                    assertThat(it.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                }
            assertThat(snapshot(tenant)).isEqualTo(before)
            assertThat(TABLES.sumOf { rows(it, tenant).size }).isZero()
        }

    @Test
    fun `INV-AC-8224 the look-alike caller is refused by the generated check and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val before = snapshot(tenant)
            val response = served.provision(FirstAdministratorFixture.FIRST, LOOK_ALIKE, tenant.toString())
            assertThat(response.status).isEqualTo(403)
            assertThat(response.contentAsString).contains("SRT-004")
            assertThat(snapshot(tenant)).isEqualTo(before)
        }
}
