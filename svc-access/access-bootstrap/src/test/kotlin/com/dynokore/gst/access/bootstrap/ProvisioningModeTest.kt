package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * VP-02, how grants are provisioned (INV-REQ-1263): grants are made by hand on a new tenant, each with role, scope and
 * validity, and the selection of SCIM is refused while the variation point is planned. `setPolicy` is reached through
 * the mounted servlet with a test-only principal source (the production source is proved in
 * [ProductionPolicyMountingTest]).
 */
class ProvisioningModeTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val admin = "user:tenant-admin"

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withBean(
                EndUserPrincipalResolver::class.java,
                {
                    TestTokenPrincipals(mapOf("token-admin" to EndUserPrincipal(admin, tenant, emptySet())))
                },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun servlet(context: AssertableWebApplicationContext): HttpServlet =
        context
            .getBeansOfType(ServletRegistrationBean::class.java)
            .values
            .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
            .servlet as HttpServlet

    private fun select(
        context: AssertableWebApplicationContext,
        option: String,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-02")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token-admin")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        // the policy writer refuses an effectiveDate before the business date (Asia/Singapore, INV-Q-2940)
        val today = BusinessDate.today(context.getBean(Clock::class.java))
        request.setContent(
            (
                """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"$option"}],""" +
                    """"effectiveDate":"$today","reason":"provisioning"}"""
            ).toByteArray(),
        )
        return MockHttpServletResponse().also { servlet(context).service(request, it) }
    }

    private fun mode(context: AssertableWebApplicationContext): String =
        context
            .getBean(PolicyReader::class.java)
            .resolve(
                VariationPointKey("VP-02"),
                PolicyScope.tenant(tenant),
                BusinessDate.today(context.getBean(Clock::class.java)),
            ).selected
            .single()
            .key

    private fun handGrant(context: AssertableWebApplicationContext) =
        context.getBean(TenantContext::class.java).bind(tenant, admin) {
            val repository = context.getBean(GrantRepository::class.java)
            context
                .getBean(TransactionRunner::class.java)
                .inTransaction {
                    repository.findByIdentity(IdentityRef("IDN-HAND"))
                }.single()
        }

    private fun byHand(context: AssertableWebApplicationContext) {
        Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
        val today = LocalDate.now(context.getBean(Clock::class.java).withZone(ZoneOffset.UTC))
        context.getBean(TenantContext::class.java).bind(tenant, admin) {
            context
                .getBean(GrantsController::class.java)
                .grantRole(
                    UUID.randomUUID(),
                    GrantRequest(
                        GrantRequest.GranteeKind.STAFF,
                        "IDN-HAND",
                        "tax.approver",
                        "tenant:$tenant",
                        today,
                        today.plusDays(90),
                    ),
                )
        }
    }

    @Test
    fun `INV-AC-2245 grants are made by hand on a new tenant and the SCIM selection is refused while planned`() =
        run { context ->
            assertThat(mode(context)).isEqualTo("BY_HAND")
            byHand(context)
            val response = select(context, "SCIM")
            assertThat(response.status).describedAs(response.contentAsString).matches { it !in 200..299 }
            // refused because the variation point is planned, not for another reason
            assertThat(response.contentAsString).describedAs("refusal").containsIgnoringCase("planned")
            assertThat(mode(context)).isEqualTo("BY_HAND")
            val grant = handGrant(context)
            assertThat(grant.source).isEqualTo(GrantSource.BY_HAND)
            assertThat(grant.role.wire).isNotBlank()
            assertThat(grant.scope).isNotNull()
            assertThat(grant.window.from).isNotNull()
        }

    @Test
    fun `INV-AC-8558 a grant by hand keeps role, scope and validity, SCIM stays refused`() =
        run { context ->
            byHand(context)
            val grant = handGrant(context)
            assertThat(grant.role.wire).isEqualTo("tax.approver")
            assertThat(grant.window.until).isNotNull()
            assertThat(select(context, "SCIM").status).matches { it !in 200..299 }
            assertThat(mode(context)).isEqualTo("BY_HAND")
        }
}
