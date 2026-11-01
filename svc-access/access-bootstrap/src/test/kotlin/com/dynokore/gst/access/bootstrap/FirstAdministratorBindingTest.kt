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
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.first
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

/**
 * INV-AC-8224: the tenant binding of the body tenantRef (INV-Q-14267 B).
 * Through the real context over a real PostgreSQL with forced row-level security, the generated caller check and
 * the request filters in front of the controller; the doubles are those of ServedDoubles.
 */
class FirstAdministratorBindingTest {
    // ---- the tenant binding ----

    @Test
    fun `INV-AC-8224 a tenantless operator token is bound to tenantRef and writes a row of that tenant only`() =
        withService { served ->
            val tenant = newTenant()
            val other = newTenant()
            assertThat(served.first(tenant, auth = OPERATOR_TOKEN).status).isEqualTo(201)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(adminGrants(other)).isEmpty()
            val audit =
                served.application
                    .getBean(CapturedEvents::class.java)
                    .envelopes
                    .filter {
                        it.type.contains("AuditEntryRecorded") && it.dataJson.contains("provisionFirstAdministrator")
                    }
            assertThat(audit).isNotEmpty
            assertThat(audit.last().dataJson).contains("operator-1")
        }

    @Test
    fun `INV-AC-8224 a token of another tenant is refused with 403 and no row exists in either tenant`() =
        withService { served ->
            val a = com.dynokore.gst.access.bootstrap.served.TENANT_A
            val b = com.dynokore.gst.access.bootstrap.served.TENANT_B
            val before = snapshot(a) to snapshot(b)
            val response = served.first(a, auth = USER_B_TOKEN)
            assertThat(response.status).isEqualTo(403)
            assertThat(snapshot(a) to snapshot(b)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 a token of the same tenant is bound to it`() =
        withService { served ->
            val a = com.dynokore.gst.access.bootstrap.served.TENANT_A
            // the shared tenant A may already hold an admin from another test run of this database: use the status
            val response = served.first(a, auth = USER_A_TOKEN)
            assertThat(response.status).isIn(201, 409)
        }

    @Test
    fun `INV-AC-8224 a tenantRef that is not the exact lower-case tenant id is 400 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val before = served.stored()
            listOf(
                tenant.toString().uppercase(),
                tenant.toString() + " ",
                "not-a-tenant",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "",
            ).forEach { text ->
                val response = served.provision(FIRST, CONTROL_PLANE, text)
                assertThat(response.status).describedAs("tenantRef '$text'").isEqualTo(400)
            }
            assertThat(served.stored()).isEqualTo(before)
        }
}
