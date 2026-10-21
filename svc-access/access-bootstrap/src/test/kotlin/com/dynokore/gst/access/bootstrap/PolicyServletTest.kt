package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The principal source of the servlet tests only: fixed test tokens to principals. The production source is the identity
 * binding of INV-T-4938; this class lives under src/test and no production class implements the port (INV-Q-8183).
 */
class TestTokenPrincipals(
    private val principals: Map<String, EndUserPrincipal>,
) : EndUserPrincipalResolver {
    override fun resolve(authorization: String?): EndUserPrincipal? =
        authorization?.removePrefix("Bearer ")?.takeIf { authorization.startsWith("Bearer ") }?.let(principals::get)
}

/**
 * INV-AC-2767 through the mounted PolicyCommandServlet (INV-T-4935, ruling INV-Q-8183): the servlet registered by
 * service-runtime at PolicyCommandServlet.MAPPING answers over the real PolicyWriter, the TenantPolicyRoleResolver binding
 * and the IdempotencyStore on the access schema. The principal comes from a test-only resolver.
 */
class PolicyServletTest {
    /** Own tenant per test: the database is shared, and a stored policy would otherwise reach the next test. */
    private val tenant = TenantId(UUID.randomUUID())

    private companion object {
        private const val ADMIN = "user:tenant-admin"
        private const val PERSON = "IDN-TWO"
    }

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withBean(EndUserPrincipalResolver::class.java, { principals() })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun principals(): EndUserPrincipalResolver =
        TestTokenPrincipals(
            mapOf(
                "token-admin" to EndUserPrincipal(ADMIN, tenant, emptySet()),
                "token-nobody" to EndUserPrincipal("user:nobody", tenant, emptySet()),
                "token-entity-admin" to EndUserPrincipal("user:entity-admin", tenant, emptySet()),
            ),
        )

    private fun setUp(context: AssertableWebApplicationContext) {
        val tenants = context.getBean(TenantContext::class.java)
        // a fixture: no production path creates the first tenant.admin (INV-Q-8185)
        Seeds.grant(context, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
        Seeds.grant(context, tenant, SeedSpec("user:entity-admin", Role.ENTITY_ADMIN))
        context
            .getBean(StructureReplica::class.java)
            .accept(
                OrganisationStructureChanged(tenant, "ENT-1", StructureChange.CREATED, BoundaryFixture.entityNode()),
            )
        val grants = context.getBean(GrantsController::class.java)
        listOf("tax.approver", "tenant.admin").forEach { role ->
            tenants.bind(tenant, ADMIN) {
                grants.grantRole(
                    UUID.randomUUID(),
                    GrantRequest(
                        GrantRequest.GranteeKind.STAFF,
                        PERSON,
                        role,
                        "entity:ENT-1",
                        LocalDate.now().minusDays(1),
                        LocalDate.now().plusDays(400),
                    ),
                )
            }
        }
    }

    private fun landing(context: AssertableWebApplicationContext): String =
        context.getBean(TenantContext::class.java).bind(tenant, PERSON) {
            context
                .getBean(GrantsController::class.java)
                .resolveLandingScreen()
                .body!!
                .role
        }

    private fun servlet(context: AssertableWebApplicationContext): HttpServlet {
        val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
        val policy = registrations.single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
        return policy.servlet as HttpServlet
    }

    private fun setPolicy(
        context: AssertableWebApplicationContext,
        token: String?,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-73")
        request.contentType = "application/json"
        token?.let { request.addHeader("Authorization", "Bearer $it") }
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val today = LocalDate.now(ZoneId.of("Asia/Singapore"))
        request.setContent(
            """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"TENANT_PRECEDENCE","value":["tax.approver","tenant.admin"]}],"effectiveDate":"$today","reason":"precedence"}"""
                .toByteArray(),
        )
        val response = MockHttpServletResponse()
        servlet(context).service(request, response)
        return response
    }

    @Test
    fun `INV-AC-2767 setPolicy through the mounted servlet by a tenant admin changes the landing order`() =
        run { context ->
            setUp(context)
            assertThat(landing(context)).isEqualTo("tenant.admin")
            val response = setPolicy(context, "token-admin")
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            assertThat(response.contentAsString).contains("settingId")
            assertThat(landing(context)).isEqualTo("tax.approver")
        }

    @Test
    fun `INV-AC-2767 setPolicy without a token is 401 and stores nothing`() =
        run { context ->
            setUp(context)
            val response = setPolicy(context, null)
            assertThat(response.status).isEqualTo(401)
            assertThat(response.contentAsString).contains("SRT-003")
            assertThat(landing(context)).isEqualTo("tenant.admin")
        }

    @Test
    fun `INV-AC-2767 setPolicy by an actor with no grant is 403 SRT-004 and stores nothing`() =
        run { context ->
            setUp(context)
            val response = setPolicy(context, "token-nobody")
            assertThat(response.status).isEqualTo(403)
            assertThat(response.contentAsString).contains("SRT-004")
            assertThat(landing(context)).isEqualTo("tenant.admin")
        }

    @Test
    fun `INV-AC-2767 setPolicy by a tenant-scope entity admin is refused with POL-004 and stores nothing`() =
        run { context ->
            setUp(context)
            val response = setPolicy(context, "token-entity-admin")
            assertThat(response.status).isGreaterThanOrEqualTo(400)
            assertThat(response.contentAsString).contains("POL-004")
            assertThat(landing(context)).isEqualTo("tenant.admin")
        }
}
