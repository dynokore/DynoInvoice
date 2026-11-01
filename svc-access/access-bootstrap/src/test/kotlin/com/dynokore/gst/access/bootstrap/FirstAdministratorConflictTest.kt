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
 * INV-AC-8224: the 409 rule over the tenant admin rows of a tenant, and concurrent calls.
 * Through the real context over a real PostgreSQL with forced row-level security, the generated caller check and
 * the request filters in front of the controller; the doubles are those of ServedDoubles.
 */
class FirstAdministratorConflictTest {
    // ---- the 409 rule ----

    private fun refusedFor(
        seed: (TenantId, ServedContext) -> Unit,
        expected: Int,
    ) = withService { served ->
        val tenant = newTenant()
        seed(tenant, served)
        val before = snapshot(tenant)
        val response = served.first(tenant)
        assertThat(response.status).isEqualTo(expected)
        if (expected == 409) assertThat(snapshot(tenant)).isEqualTo(before)
    }

    @Test
    fun `INV-AC-8224 an ACTIVE tenant admin row refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE") }, 409)

    @Test
    fun `INV-AC-8224 a PENDING tenant admin row refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "PENDING") }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row created through grantRole refuses the call with 409`() =
        refusedFor({ t, served ->
            val context = served.application
            val tenants = context.getBean(TenantContext::class.java)
            tenants.bind(t, "IDN-GRANTER") {
                context.getBean(GrantRoleUseCase::class.java).handle(
                    GrantRoleCommand(
                        tenant = TenantRef(t.toString()),
                        actor = "IDN-GRANTER",
                        identity = IdentityRef("IDN-VIA-GRANT-ROLE"),
                        granteeKind = GranteeKind.STAFF,
                        roleWire = Role.TENANT_ADMIN.wire,
                        scope = Scope.Tenant(TenantRef(t.toString())),
                        window = ValidityWindow(context.getBean(Clock::class.java).instant(), null),
                    ),
                )
            }
            assertThat(adminGrants(t)).hasSize(1)
        }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row from a directory group mapping refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE", source = "DIRECTORY_GROUPS") }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row of any scope level refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE", scope = "ENTITY" to "ENT-1") }, 409)

    @Test
    fun `INV-AC-8224 a tenant whose only tenant admin rows are REVOKED is admitted`() =
        refusedFor({ t, _ -> seedAdminRow(t, "REVOKED") }, 201)

    @Test
    fun `INV-AC-8224 a tenant admin row of another tenant does not block the call`() =
        withService { served ->
            val other = newTenant()
            val tenant = newTenant()
            seedAdminRow(other, "ACTIVE")
            seedAdminRow(other, "PENDING")
            assertThat(served.first(tenant).status).isEqualTo(201)
            assertThat(adminGrants(other)).hasSize(2)
            assertThat(adminGrants(tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8224 two concurrent calls for one tenant with two keys store one grant and one is 409`() =
        withService { served ->
            val tenant = newTenant()
            val statuses = concurrently(2) { served.first(tenant, UUID.randomUUID()).status }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(rows("access.first_administrator_invitation", tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8224 two concurrent calls with the same key store one grant and answer one invitation reference`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            val answers = concurrently(2) { served.first(tenant, key) }
            assertThat(answers.map { it.status }).containsOnly(201)
            assertThat(answers.map { json(it)["invitationRef"].asString() }.toSet()).hasSize(1)
            assertThat(answers.map { json(it)["invitationCode"].asString() }.toSet()).hasSize(1)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(rows("access.first_administrator_invitation", tenant)).hasSize(1)
        }

    private fun <T> concurrently(
        count: Int,
        call: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val ready = CountDownLatch(count)
            val go = CountDownLatch(1)
            val futures =
                (1..count).map {
                    pool.submit(
                        Callable {
                            ready.countDown()
                            go.await()
                            call()
                        },
                    )
                }
            ready.await()
            go.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}
