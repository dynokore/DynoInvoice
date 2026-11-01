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
 * INV-AC-8224: the code key (fail closed, rotation) and the placeholder identity of the pending grant.
 * Through the real context over a real PostgreSQL with forced row-level security, the generated caller check and
 * the request filters in front of the controller; the doubles are those of ServedDoubles.
 */
class FirstAdministratorKeyTest {
    // ---- fail closed on the code key ----

    @Test
    fun `INV-AC-8224 a store holding only the cursor key makes the first call 503 SRT-011 and stores nothing`() =
        withService(mounted = FirstAdministratorFixture.mount(emptyMap())) { served ->
            val tenant = newTenant()
            val response = served.first(tenant)
            assertThat(response.status).isEqualTo(503)
            assertThat(response.contentAsString).contains("SRT-011")
            assertThat(snapshot(tenant).values.flatten()).isEmpty()
        }

    @Test
    fun `INV-AC-8224 a key shorter than 32 bytes is unavailable`() =
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v1" to "too-short"))) { served ->
            val tenant = newTenant()
            assertThat(served.first(tenant).status).isEqualTo(503)
            assertThat(snapshot(tenant).values.flatten()).isEmpty()
        }

    @Test
    fun `INV-AC-8224 a repeat with a missing key version is 503 and a rotation keeps the old code`() {
        val v1 = TestSecrets.INVITATION_KEY
        val v2 = "test-invitation-code-key-v2-0123456789abcdef"
        val tenant = newTenant()
        val key = UUID.randomUUID()
        var firstBody = ""
        var firstRef = ""
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v1" to v1))) { served ->
            val first = served.first(tenant, key)
            assertThat(first.status).isEqualTo(201)
            firstBody = first.contentAsString
            firstRef = json(first)["invitationRef"].asString()
        }
        // the key of the row's version is gone: the repeat fails closed
        val onlyV2 = FirstAdministratorFixture.mount(mapOf("v2" to v2))
        withService(mounted = onlyV2, version = "v2") { served ->
            val before = snapshot(tenant)
            val repeat = served.first(tenant, key)
            assertThat(repeat.status).isEqualTo(503)
            assertThat(repeat.contentAsString)
                .contains("SRT-011")
                .doesNotContain(json(firstBody)["invitationCode"].asString())
            assertThat(snapshot(tenant)).isEqualTo(before)
        }
        // both versions are mounted and the current one is v2
        val both = FirstAdministratorFixture.mount(mapOf("v1" to v1, "v2" to v2))
        withService(mounted = both, version = "v2") { served ->
            val repeat = served.first(tenant, key)
            assertThat(repeat.status).isEqualTo(201)
            assertThat(json(repeat)["invitationCode"].asString()).isEqualTo(expectedCode(tenant, firstRef, v1))
            val fresh = newTenant()
            val created = served.first(fresh)
            assertThat(created.status).isEqualTo(201)
            val stored = rows("access.first_administrator_invitation", fresh).single()
            val row = FirstAdministratorFixture.JSON.readTree(stored)
            assertThat(row["code_key_version"].asString()).isEqualTo("v2")
            assertThat(json(created)["invitationCode"].asString())
                .isEqualTo(expectedCode(fresh, json(created)["invitationRef"].asString(), v2))
        }
    }

    // ---- the pending-grant identity ----

    @Test
    fun `INV-AC-8224 a revoked pending grant keeps its identity row`() =
        withService { served ->
            val tenant = newTenant()
            val grantRef = json(served.first(tenant))["grantRef"].asString()
            val context = served.application
            context.getBean(TenantContext::class.java).bind(tenant, "IDN-REVOKER") {
                context.getBean(RevokeGrantUseCase::class.java).handle("IDN-REVOKER", GrantRef(grantRef))
            }
            assertThat(rows("access.role_grant", tenant).single()).contains("\"status\": \"REVOKED\"")
            assertThat(rows("access.identity", tenant)).hasSize(1)
            // a REVOKED-only tenant is admitted again
            assertThat(served.first(tenant).status).isEqualTo(201)
        }

    @Test
    fun `INV-AC-8224 a pending grant authorises nothing for the invitation identity`() =
        withService { served ->
            val tenant = newTenant()
            val ref = json(served.first(tenant))["invitationRef"].asString()
            val context = served.application
            val grants = context.getBean(GrantRepository::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val held =
                context.getBean(TenantContext::class.java).bind(tenant, "IDN-READER") {
                    tx.inTransaction { grants.findByIdentity(IdentityRef("invitation:$ref")) }
                }
            assertThat(held).hasSize(1)
            val now = context.getBean(Clock::class.java).instant()
            val permissions = FixtureMatrix.matrix.permissionsOf(Role.TENANT_ADMIN)
            assertThat(permissions).isNotEmpty
            held.forEach { grant ->
                permissions.forEach { permission ->
                    val target = Target(EntityRef("ENT-1"))
                    val membership = EntityMembership(TenantRef(tenant.toString()), null)
                    assertThat(grant.authorises(permission, target, membership, FixtureMatrix.matrix, now)).isFalse()
                }
            }
        }
}
