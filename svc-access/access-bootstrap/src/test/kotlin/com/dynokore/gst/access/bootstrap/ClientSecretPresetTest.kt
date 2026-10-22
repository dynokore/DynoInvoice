package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.TenantPolicyRoleResolver
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse.Decision
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * VP-77 (INV-REQ-2760): client_secret_basic is available under the default preset, switched off by the
 * regulated-private preset and never switched back on; the ap-callback principal keeps its own method (INV-AC-2770).
 * The write goes through the mounted `setPolicy` servlet over the real PolicyWriter; the roles come from the
 * `PolicyRoleResolver` seam. The preset takes effect 30 days ahead and the test clock is moved past it, so no other
 * test sees it (an installation value cannot be taken back).
 */
class ClientSecretPresetTest {
    private val vp77 = VariationPointKey("VP-77")
    private val operator = "user:deployment-operator"
    private val today = LocalDate.now(ZoneId.of("Asia/Singapore"))
    private val effective = today.plusDays(30)

    private fun resolver(heldAt: ScopeLevel): PolicyRoleResolver =
        PolicyRoleResolver { actor, _, level, _, _ ->
            if (actor == operator && level == heldAt) setOf("deployment.operator") else emptySet()
        }

    private fun run(
        roles: PolicyRoleResolver? = null,
        block: (PrincipalKit) -> Unit,
    ) {
        val principals = TestTokenPrincipals(mapOf("token-operator" to EndUserPrincipal(operator, null, emptySet())))
        var runner = BoundaryFixture.runner().withBean(EndUserPrincipalResolver::class.java, { principals })
        if (roles != null) runner = runner.withBean(PolicyRoleResolver::class.java, { roles })
        runner.run { context ->
            context.startupFailure?.let { throw it }
            block(PrincipalKit(context, grantDays = 120))
        }
    }

    private fun setPreset(
        context: AssertableWebApplicationContext,
        option: String,
        from: LocalDate,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-77")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token-operator")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(
            """{"scopeLevel":"INSTALLATION","selected":[{"option":"$option"}],"effectiveDate":"$from","reason":"preset"}"""
                .toByteArray(),
        )
        val response = MockHttpServletResponse()
        val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
        val policy = registrations.single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
        (policy.servlet as HttpServlet).service(request, response)
        return response
    }

    /** The option keys of VP-77 stored for the installation on [on]. */
    private fun stored(
        kit: PrincipalKit,
        on: LocalDate,
    ): List<String> =
        kit.asAdmin {
            kit.context.getBean(TransactionRunner::class.java).inTransaction {
                kit.context
                    .getBean(PolicyReader::class.java)
                    .resolve(vp77, PolicyScope.installation(), on)
                    .selected
                    .map { it.key }
            }
        }

    @Test
    fun `INV-AC-2770 client_secret_basic switched off cannot be switched back on and ap-callback keeps its method`() =
        run(resolver(ScopeLevel.INSTALLATION)) { kit ->
            val reporting = kit.create("REPORTING", scope = "entity:ENT-1", method = PrincipalKit.SECRET)
            val secret = reporting.credential!!.clientSecret!!
            val ap =
                kit.create(
                    "AP_CALLBACK",
                    scope = "entity:ENT-1",
                    method = PrincipalKit.MTLS,
                    thumbprint = "ab12cd34",
                )
            assertThat(kit.authenticate(reporting.principalRef, PrincipalKit.AUTH_SECRET, secret).decision)
                .isEqualTo(Decision.AUTHENTICATED)
            val off = setPreset(kit.context, "REGULATED_PRIVATE", effective)
            assertThat(off.status).describedAs(off.contentAsString).isBetween(200, 299)
            kit.clock.advance(Duration.ofDays(31))
            assertThat(stored(kit, BusinessDate.today(kit.clock))).containsExactly("REGULATED_PRIVATE")
            // the attempt to switch it back on is refused and the stored value stays
            val back = setPreset(kit.context, "SAAS_STANDARD", effective.plusDays(5))
            assertThat(back.status).describedAs(back.contentAsString).isGreaterThanOrEqualTo(400)
            assertThat(stored(kit, BusinessDate.today(kit.clock))).containsExactly("REGULATED_PRIVATE")
            assertThat(stored(kit, effective.plusDays(10))).containsExactly("REGULATED_PRIVATE")
            // client_secret_basic is refused now, the ap-callback principal still authenticates by its own method
            assertThat(kit.authenticate(reporting.principalRef, PrincipalKit.AUTH_SECRET, secret).decision)
                .isEqualTo(Decision.REFUSED)
            assertThat(kit.authenticate(ap.principalRef, PrincipalKit.AUTH_MTLS, "ab12cd34").decision)
                .isEqualTo(Decision.AUTHENTICATED)
            // no new client_secret_basic credential is issued either (create and rotate are 400)
            assertThat(
                PrincipalKit.refusal { kit.create("INGEST", scope = "entity:ENT-2", method = PrincipalKit.SECRET) },
            ).contains("SRT-001")
            assertThat(kit.principalCount()).isEqualTo(2)
            assertThat(PrincipalKit.refusal { kit.rotate(reporting.principalRef, 5, PrincipalKit.ROTATE_SECRET) })
                .contains("SRT-001")
        }

    @Test
    fun `probe a resolver that holds the role only at the level TENANT cannot set the preset`() =
        run(resolver(ScopeLevel.TENANT)) { kit ->
            val response = setPreset(kit.context, "REGULATED_PRIVATE", effective)
            assertThat(response.status).isEqualTo(403)
            assertThat(response.contentAsString).contains("SRT-004")
            assertThat(stored(kit, effective.plusDays(1))).doesNotContain("REGULATED_PRIVATE")
        }

    @Test
    fun `the production resolver returns no role at the level INSTALLATION for a holder of deployment operator`() =
        run { kit ->
            val subject = "user:operator-holder"
            Seeds.grant(kit.context, kit.tenant, SeedSpec(subject, Role.DEPLOYMENT_OPERATOR))
            val production = kit.context.getBean(PolicyRoleResolver::class.java)
            assertThat(production).isInstanceOf(TenantPolicyRoleResolver::class.java)
            // positive control: the same subject holds the role at the level TENANT
            assertThat(production.rolesOf(subject, kit.tenant, ScopeLevel.TENANT, kit.tenant.toString(), null))
                .contains("deployment.operator")
            assertThat(production.rolesOf(subject, kit.tenant, ScopeLevel.INSTALLATION, null, null)).isEmpty()
            assertThat(production.rolesOf(subject, TenantId.PLATFORM, ScopeLevel.INSTALLATION, null, null)).isEmpty()
        }
}
