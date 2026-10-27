package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.Connection
import java.util.UUID

/**
 * INV-AC-9789 (INV-REQ-862 behaviours 4 and 5, INV-Q-8687 A): `authorise` with the scope `platform` reads the
 * operator-role grants of the platform tenant. Rows are inserted as the migration login (no seed row exists), and no
 * row of access.role_grant or access.identity changes in any call.
 */
class AuthorisePlatformScopeTest {
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val customer = "0c000000-0000-4000-8000-00000000000c"

    private val run = UUID.randomUUID().toString().take(8)

    private fun id(name: String) = "user:$name-$run"

    private data class Grant(
        val tenant: String,
        val name: String,
        val role: String,
        val status: String = "ACTIVE",
        val level: String = "TENANT",
        val from: String = "now() - interval '1 day'",
        val until: String = "now() + interval '30 days'",
    )

    private fun row(grant: Grant) {
        val identity = id(grant.name)
        val ref = "GRT-" + UUID.randomUUID().toString().take(8)
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) select gen_random_uuid(), " +
                        "'${grant.tenant}', '$identity', 'STAFF', " +
                        "now(), 'mig', now(), 'mig', 0 where not exists (select 1 from access.identity " +
                        "where tenant_id = '${grant.tenant}' and identity_ref = '$identity')",
                )
                it.execute(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, valid_from, valid_until, status, grant_source, created_at, created_by, " +
                        "updated_at, updated_by, version) values (gen_random_uuid(), " +
                        "'${grant.tenant}', '$ref', '$identity', " +
                        "'${grant.role}', '${grant.level}', '${grant.tenant}', " +
                        "${grant.from}, ${grant.until}, '${grant.status}', 'BY_HAND', now(), 'mig', now(), " +
                        "'mig', 0)",
                )
            }
        }
    }

    private fun snapshot(): String =
        BoundaryFixture.asSuperuser { c: Connection ->
            c.createStatement().use { s ->
                val grants =
                    s.executeQuery("select * from access.role_grant order by id").use { r -> dump(r) }
                val identities =
                    s.executeQuery("select * from access.identity order by id").use { r -> dump(r) }
                grants + "|" + identities
            }
        }

    private fun dump(r: java.sql.ResultSet): String {
        val n = r.metaData.columnCount
        val out = StringBuilder()
        while (r.next()) (1..n).forEach { out.append(r.getString(it)).append(',') }
        return out.toString()
    }

    private val customerTenant = TenantId(UUID.fromString(customer))
    private lateinit var context: AssertableWebApplicationContext

    /** An entity of the customer tenant, and the call as the inbound adapter makes it: bound to that tenant. */
    private fun inCustomer(block: () -> AuthoriseResponse): AuthoriseResponse {
        context.getBean(StructureReplica::class.java).accept(
            OrganisationStructureChanged(
                customerTenant,
                "ENT-P",
                StructureChange.CREATED,
                BoundaryFixture.entityNode(),
            ),
        )
        return context.getBean(TenantContext::class.java).bind(customerTenant, "caller") { block() }
    }

    /** The rows this test inserted, so that no platform row outlives it for the other tests of the module. */
    private fun removeRows() {
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute("delete from access.role_grant where identity_ref like '%-$run'")
                it.execute("delete from access.identity where identity_ref like '%-$run'")
            }
        }
    }

    private fun run(block: (AuthoriseController) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true, requirements = null)
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                this.context = context
                try {
                    block(context.getBean(AuthoriseController::class.java))
                } finally {
                    removeRows()
                }
            }
    }

    private fun AuthoriseController.ask(
        identity: String,
        permission: String,
        scope: String = "platform",
    ): AuthoriseResponse = authorise(identity, permission, scope).body!!

    private fun allow(
        r: AuthoriseResponse,
        role: String,
    ) {
        assertThat(r.decision).isEqualTo(AuthoriseResponse.Decision.ALLOW)
        assertThat(r.role).isEqualTo(role)
    }

    private fun deny(r: AuthoriseResponse) {
        assertThat(r.decision).isEqualTo(AuthoriseResponse.Decision.DENY)
        assertThat(r.reasonCode).isNotBlank()
    }

    @Test
    fun `INV-AC-9789 the platform grants allow their role for scope platform and nothing else`() =
        run { api ->
            row(Grant(platform, "rm", "release.manager"))
            row(Grant(platform, "ca", "commercial.admin"))
            row(Grant(customer, "cust-rm", "release.manager"))
            val before = snapshot()
            allow(api.ask(id("rm"), "release-train.publish"), "release.manager")
            deny(api.ask(id("rm"), "plan.manage"))
            allow(api.ask(id("ca"), "plan.manage"), "commercial.admin")
            allow(api.ask(id("ca"), "entitlement.manage"), "commercial.admin")
            allow(api.ask(id("ca"), "licence.manage"), "commercial.admin")
            deny(api.ask(id("ca"), "release-train.publish"))
            // a grant held only in a customer tenant is invisible under the platform binding
            deny(api.ask(id("cust-rm"), "release-train.publish"))
            // a platform grant does not answer for a customer entity
            deny(inCustomer { api.ask(id("rm"), "release-train.publish", "entity:ENT-P") })
            deny(api.ask(id("nobody"), "release-train.publish"))
            // the same answer when the inbound adapter has bound a tenant before the call
            allow(inCustomer { api.ask(id("rm"), "release-train.publish") }, "release.manager")
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9789 a pending, revoked or expired platform grant is denied`() =
        run { api ->
            row(Grant(platform, "pending", "release.manager", status = "PENDING"))
            row(Grant(platform, "revoked", "release.manager", status = "REVOKED"))
            row(
                Grant(
                    platform,
                    "expired",
                    "release.manager",
                    from = "now() - interval '10 days'",
                    until = "now() - interval '1 day'",
                ),
            )
            row(
                Grant(
                    platform,
                    "future",
                    "release.manager",
                    from = "now() + interval '1 day'",
                    until = "now() + interval '2 days'",
                ),
            )
            row(Grant(platform, "group", "release.manager", level = "GROUP"))
            val before = snapshot()
            listOf("pending", "revoked", "expired", "future", "group").forEach {
                deny(api.ask(id(it), "release-train.publish"))
            }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9789 every look-alike scope is denied and reads no grant`() =
        run { api ->
            row(Grant(platform, "rm", "release.manager"))
            val before = snapshot()
            listOf(
                "Platform",
                "platform ",
                " platform",
                "platform:x",
                "entity:platform",
                "PLATFORM",
                "",
                "platform\n",
            ).forEach {
                val r = inCustomer { api.ask(id("rm"), "release-train.publish", it) }
                deny(r)
                assertThat(r.reasonCode).describedAs("[$it]").isIn("BAD_SCOPE", "NO_ENTITY")
            }
            assertThat(api.ask(id("rm"), "release-train.publish", "Platform").reasonCode).isEqualTo("BAD_SCOPE")
            assertThat(inCustomer { api.ask(id("rm"), "release-train.publish", "entity:platform") }.reasonCode)
                .isEqualTo("NO_ENTITY")
            assertThat(api.ask(id("rm"), " ").reasonCode).isEqualTo("BAD_PERMISSION")
            assertThat(snapshot()).isEqualTo(before)
        }
}
