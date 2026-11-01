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

private val SHAPE_COLUMNS =
    setOf("role", "scope_level", "status", "grant_source", "invited_email", "code_key_version", "grantee_kind")

/**
 * INV-AC-8224: who may call which operation (generated check) and the installer form.
 * Through the real context over a real PostgreSQL with forced row-level security, the generated caller check and
 * the request filters in front of the controller; the doubles are those of ServedDoubles.
 */
class FirstAdministratorCallerTest {
    // ---- the caller ----

    private fun refusedCall(
        caller: String?,
        operation: String,
        status: Int,
        code: String?,
        auth: String? = OPERATOR_TOKEN,
    ) = withService { served ->
        val tenant = newTenant()
        val before = snapshot(tenant)
        val events = served.stored()
        val response = served.provision(operation, caller, tenant.toString(), auth = auth)
        assertThat(response.status).isEqualTo(status)
        if (code != null) assertThat(response.contentAsString).contains(code)
        assertThat(snapshot(tenant)).isEqualTo(before)
        assertThat(served.stored()).isEqualTo(events)
    }

    @Test
    fun `INV-AC-8224 the look-alike service is refused with 403 SRT-004 and stores nothing`() =
        refusedCall(FirstAdministratorFixture.LOOK_ALIKE, FIRST, 403, "SRT-004")

    @Test
    fun `INV-AC-8224 the installer workload is refused on provisionFirstAdministrator`() =
        refusedCall(INSTALLER, FIRST, 403, "SRT-004")

    @Test
    fun `INV-AC-8224 control-plane-svc is refused on provisionInstallationFirstAdministrator`() =
        refusedCall(CONTROL_PLANE, INSTALLATION, 403, "SRT-004", auth = null)

    @Test
    fun `INV-AC-8224 another service is refused on both operations`() {
        refusedCall(OTHER, FIRST, 403, "SRT-004")
        refusedCall(OTHER, INSTALLATION, 403, "SRT-004", auth = null)
    }

    @Test
    fun `INV-AC-8224 a person with a tenant admin token and another service's workload token is refused with 403`() =
        refusedCall(OTHER, FIRST, 403, "SRT-004", auth = USER_A_TOKEN)

    @Test
    fun `INV-AC-8224 a person alone, with no workload token, is refused with 401`() {
        refusedCall(null, FIRST, 401, null, auth = USER_A_TOKEN)
        refusedCall(null, INSTALLATION, 401, null, auth = null)
    }

    // ---- the installer form ----

    @Test
    fun `INV-AC-8224 the installer operation stores the same rows as the first and repeats and refuses alike`() =
        withService { served ->
            val viaPlane = newTenant()
            val viaInstaller = newTenant()
            val a = served.first(viaPlane)
            val key = UUID.randomUUID()
            val b = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), key, auth = null)
            assertThat(b.status).isEqualTo(a.status).isEqualTo(201)
            assertThat(json(b)["status"].asString()).isEqualTo("PENDING")
            assertThat(json(b)["invitationCode"].asString())
                .isEqualTo(expectedCode(viaInstaller, json(b)["invitationRef"].asString()))

            fun shape(tenant: TenantId) =
                FirstAdministratorFixture.TABLES.associateWith { table ->
                    rows(table, tenant).map {
                        FirstAdministratorFixture.JSON.readTree(it).let { node ->
                            node
                                .propertyNames()
                                .asSequence()
                                .filter { n -> n in SHAPE_COLUMNS }
                                .associateWith { n -> node[n].asString() }
                        }
                    }
                }
            val planeShape = shape(viaPlane)
            val installerShape = shape(viaInstaller)
            assertThat(installerShape["access.role_grant"]).isEqualTo(planeShape["access.role_grant"])
            assertThat(installerShape["access.identity"]).isEqualTo(planeShape["access.identity"])
            assertThat(installerShape["access.first_administrator_invitation"])
                .isEqualTo(planeShape["access.first_administrator_invitation"])
            val before = snapshot(viaInstaller)
            val repeat = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), key, auth = null)
            assertThat(repeat.status).isEqualTo(201)
            assertThat(json(repeat)["invitationRef"].asString()).isEqualTo(json(b)["invitationRef"].asString())
            assertThat(json(repeat)["invitationCode"].asString()).isEqualTo(json(b)["invitationCode"].asString())
            assertThat(snapshot(viaInstaller)).isEqualTo(before)
            val refused = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), auth = null)
            assertThat(refused.status).isEqualTo(409)
            assertThat(snapshot(viaInstaller)).isEqualTo(before)
            val held = newTenant()
            seedAdminRow(held, "ACTIVE")
            assertThat(served.provision(INSTALLATION, INSTALLER, held.toString(), auth = null).status).isEqualTo(409)
        }
}
