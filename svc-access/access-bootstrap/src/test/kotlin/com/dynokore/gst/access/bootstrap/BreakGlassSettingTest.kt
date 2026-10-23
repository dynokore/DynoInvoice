package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * VP-08, the break-glass pairing and session length (INV-REQ-1269), available since gst-catalogue 1.32.0: the setting
 * stored through the policy command decides the length and the pair of `unlockBreakGlass`; a window above 8 hours and a
 * pair of one person are refused as breaking the bound, never as "not offered" (INV-AC-2253, INV-AC-8559).
 */
class BreakGlassSettingTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val officer = "user:security-officer"

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withBean(
                EndUserPrincipalResolver::class.java,
                { TestTokenPrincipals(mapOf("token-officer" to EndUserPrincipal(officer, tenant, emptySet()))) },
            ).run { context ->
                context.startupFailure?.let { throw it }
                Seeds.grant(context, tenant, SeedSpec(officer, Role.SECURITY_OFFICER))
                Seeds.grant(context, tenant, SeedSpec("IDN-ADM", Role.TENANT_ADMIN))
                Seeds.grant(context, tenant, SeedSpec("IDN-APP", Role.TAX_APPROVER))
                Seeds.grant(context, tenant, SeedSpec("IDN-OP", Role.TAX_OPERATOR))
                Seeds.grant(context, tenant, SeedSpec("IDN-AUD", Role.AUDITOR))
                try {
                    block(context)
                } finally {
                    // the installation value is shared by every tenant of the database: put it back to the default
                    installation(context, 4)
                }
            }
    }

    /**
     * The installation value, written as the installation operator would: this service's `setPolicy` reaches the TENANT
     * level only (INV-T-4935), and a tenant window may not exceed the installation's (4 hours when nothing is set).
     */
    private fun installation(
        context: AssertableWebApplicationContext,
        window: Int,
    ) {
        val command =
            SetPolicyCommand(
                key = VariationPointKey("VP-08"),
                scope = PolicyScope.installation(),
                level = ScopeLevel.INSTALLATION,
                selected = listOf(SelectedOption("DEFAULT_PAIR_4H", """{"window":$window}""")),
                effectiveFrom = BusinessDate.today(context.getBean(Clock::class.java)),
                reason = "installation setting",
                actor = Actor("user:installer", setOf("security.officer")),
                correlation = CorrelationId("c-4938"),
                causation = CausationId("c-4938"),
            )
        context.getBean(PlatformTenantBinding::class.java).bindPlatform("user:installer") {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context.getBean(PolicyWriter::class.java).setPolicy(command)
            }
        }
    }

    private fun store(
        context: AssertableWebApplicationContext,
        option: String,
        value: String,
    ): MockHttpServletResponse {
        val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                .servlet as HttpServlet
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-08")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token-officer")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val today = BusinessDate.today(context.getBean(Clock::class.java))
        request.setContent(
            (
                """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"$option","value":$value}],""" +
                    """"effectiveDate":"$today","reason":"break-glass setting"}"""
            ).toByteArray(),
        )
        return MockHttpServletResponse().also { servlet.service(request, it) }
    }

    private fun unlock(
        context: AssertableWebApplicationContext,
        vararg approvers: String,
    ) = context.getBean(TenantContext::class.java).bind(tenant, approvers.first()) {
        context
            .getBean(BreakGlassController::class.java)
            .unlockBreakGlass(UUID.randomUUID(), UnlockBreakGlassRequest(tenant.toString(), approvers.toList()))
    }

    @Test
    fun `INV-AC-8559 INV-AC-2253 window 6 gives 6 hours, 12 is beyond the bound, one person twice is a pair of one`() =
        run { context ->
            val clock = context.getBean(java.time.Clock::class.java) as MovableClock
            clock.freeze()
            // nothing stored: the default of 4 hours
            assertThat(unlock(context, "IDN-ADM", "IDN-APP").body!!.sessionHours).isEqualTo(4)
            installation(context, 8)
            val stored = store(context, "DEFAULT_PAIR_4H", """{"window":6}""")
            assertThat(stored.status).describedAs(stored.contentAsString).isBetween(200, 299)
            val body = unlock(context, "IDN-ADM", "IDN-APP").body!!
            assertThat(body.sessionHours).isEqualTo(6)
            assertThat(body.expiresAt!!.toInstant()).isEqualTo(clock.instant().plus(Duration.ofHours(6)))
            // 12 hours: refused as beyond the 8-hour bound, not as "not offered"; the stored 6 stays
            val twelve = store(context, "DEFAULT_PAIR_4H", """{"window":12}""")
            assertThat(twelve.status).describedAs(twelve.contentAsString).isBetween(400, 499)
            assertThat(twelve.contentAsString)
                .doesNotContainIgnoringCase("planned")
                .doesNotContainIgnoringCase("not offered")
            assertThat(twelve.contentAsString).contains("POL-013")
            assertThat(unlock(context, "IDN-ADM", "IDN-APP").body!!.sessionHours).isEqualTo(6)
            // the probe: 9 is refused as well
            assertThat(store(context, "DEFAULT_PAIR_4H", """{"window":9}""").status).isBetween(400, 499)
            // a pair naming the same person twice is a pair of one person
            assertThatThrownBy { unlock(context, "IDN-ADM", "IDN-ADM") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("pair of one person")
        }

    @Test
    fun `INV-AC-8559 the stored option OTHER_NAMED_PAIR decides who may unlock`() =
        run { context ->
            val stored = store(context, "OTHER_NAMED_PAIR", """{"pair":["tax.operator","auditor"],"window":2}""")
            assertThat(stored.status).describedAs(stored.contentAsString).isBetween(200, 299)
            val body = unlock(context, "IDN-OP", "IDN-AUD").body!!
            assertThat(body.sessionHours).isEqualTo(2)
            // the default pair is not the named pair
            assertThatThrownBy { unlock(context, "IDN-ADM", "IDN-APP") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("SRT-004")
            // two people who hold the same one of the two named roles do not fill both places
            assertThatThrownBy { unlock(context, "IDN-OP", "IDN-ADM") }.isInstanceOf(ApiRefusal::class.java)
        }

    private fun versionOf(
        name: String,
        text: String,
    ): List<Int> =
        Regex("""(?m)^$name\s*=\s*"([0-9.]+)"""")
            .find(text)!!
            .groupValues[1]
            .split('.')
            .map { it.toInt() }

    private fun atLeast(
        actual: List<Int>,
        floor: List<Int>,
    ): Boolean {
        for (i in 0 until maxOf(actual.size, floor.size)) {
            val a = actual.getOrElse(i) { 0 }
            val f = floor.getOrElse(i) { 0 }
            if (a != f) return a > f
        }
        return true
    }

    @Test
    fun `the gstCatalogue pin is at least 1_32_0, the version that made VP-08 available, compared as versions`() {
        val toml = File("../gradle/libs.versions.toml").readText()
        val floor = listOf(1, 32, 0)
        assertThat(atLeast(versionOf("gstCatalogue", toml), floor)).isTrue()
        // probes: 1.30.0 (VP-08 planned) fails; 1.4.0 passes as a string compare would fail it, but not as a version
        assertThat(atLeast(listOf(1, 30, 0), floor)).isFalse()
        assertThat(atLeast(listOf(1, 100, 0), floor)).isTrue()
        assertThat(atLeast(listOf(1, 9, 9), floor)).isFalse()
    }
}
