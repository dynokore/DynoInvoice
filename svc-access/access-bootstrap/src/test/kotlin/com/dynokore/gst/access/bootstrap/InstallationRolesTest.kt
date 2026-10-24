package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.InstallationRole
import com.dynokore.gst.access.adapters.http.TenantPolicyRoleResolver
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-9212 (INV-Q-8789 A): the production resolver answers `platform.engineer` or `deployment.operator` at the
 * INSTALLATION level to the subjects listed in `access.installation-roles` and to no one else, and the property is
 * required, validated and has no default. The context is the service as composed in production over the real database.
 */
class InstallationRolesTest {
    private val vp77 = VariationPointKey("VP-77")

    private fun principals(vararg subjects: String) =
        TestTokenPrincipals(subjects.associate { "token-$it" to EndUserPrincipal(it, null, emptySet()) })

    private fun run(
        roles: Array<String> = BoundaryFixture.INSTALLATION_ROLES,
        block: (PrincipalKit) -> Unit,
    ) {
        val resolver = principals("user:op-1", "user:grantee")
        BoundaryFixture
            .runner(tokenProperties = TestSecrets.TOKEN_PROPERTIES + roles)
            .withBean(EndUserPrincipalResolver::class.java, { resolver })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(PrincipalKit(context, grantDays = 120))
            }
    }

    private fun refusedStart(roles: Array<String>?): Throwable? {
        var failure: Throwable? = null
        BoundaryFixture
            .runner(tokenProperties = TestSecrets.TOKEN_PROPERTIES + roles.orEmpty())
            .run { failure = it.startupFailure }
        return failure
    }

    private fun setPreset(
        context: AssertableWebApplicationContext,
        subject: String,
        option: String,
        from: LocalDate,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-77")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token-$subject")
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

    private fun PolicyRoleResolver.at(
        subject: String,
        tenant: TenantId,
        level: ScopeLevel = ScopeLevel.INSTALLATION,
        scopeRef: String? = null,
    ) = rolesOf(subject, tenant, level, scopeRef, null)

    @Test
    fun `INV-AC-9212 a listed subject sets VP-77 at installation scope, an unlisted grantee does not`() =
        run { kit ->
            Seeds.grant(kit.context, kit.tenant, SeedSpec("user:grantee", Role.DEPLOYMENT_OPERATOR))
            val production = kit.context.getBean(PolicyRoleResolver::class.java)
            assertThat(production).isInstanceOf(TenantPolicyRoleResolver::class.java)
            assertThat(production.at("user:op-1", kit.tenant)).isEqualTo(setOf("deployment.operator"))
            assertThat(production.at("user:eng-1", kit.tenant)).isEqualTo(setOf("platform.engineer"))
            assertThat(production.at("user:grantee", kit.tenant)).isEmpty()
            val effective = BusinessDate.today(kit.clock).plusDays(400)
            val before = stored(kit, effective.plusDays(1))
            val denied = setPreset(kit.context, "user:grantee", "REGULATED_PRIVATE", effective)
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.contentAsString).contains("SRT-004")
            assertThat(stored(kit, effective.plusDays(1))).isEqualTo(before)
            val ok = setPreset(kit.context, "user:op-1", "REGULATED_PRIVATE", effective)
            assertThat(ok.status).describedAs(ok.contentAsString).isEqualTo(200)
            kit.clock.advance(Duration.ofDays(401))
            assertThat(stored(kit, BusinessDate.today(kit.clock))).containsExactly("REGULATED_PRIVATE")
            // the tighten-only writer refuses the way back and the stored value stays
            val back = setPreset(kit.context, "user:op-1", "SAAS_STANDARD", effective.plusDays(5))
            assertThat(back.status).describedAs(back.contentAsString).isGreaterThanOrEqualTo(400)
            assertThat(stored(kit, BusinessDate.today(kit.clock))).containsExactly("REGULATED_PRIVATE")
        }

    @Test
    fun `a subject listed with both roles gets both, the tenant argument does not change the answer`() =
        run(
            arrayOf(
                "access.installation-roles[0].subject=user:both",
                "access.installation-roles[0].role=deployment.operator",
                "access.installation-roles[1].subject=user:both",
                "access.installation-roles[1].role=platform.engineer",
            ),
        ) { kit ->
            val production = kit.context.getBean(PolicyRoleResolver::class.java)
            val both = setOf("deployment.operator", "platform.engineer")
            assertThat(production.at("user:both", kit.tenant)).isEqualTo(both)
            assertThat(production.at("user:both", TenantId.PLATFORM)).isEqualTo(both)
        }

    @Test
    fun `probe look-alike subjects and other levels get the empty set`() =
        run { kit ->
            val production = kit.context.getBean(PolicyRoleResolver::class.java)
            listOf("user:op-1 ", "USER:OP-1", "user:op-10", "user:op-", "").forEach {
                assertThat(production.at(it, kit.tenant)).describedAs("[$it]").isEmpty()
            }
            listOf(ScopeLevel.GROUP, ScopeLevel.ENTITY, ScopeLevel.FEED).forEach {
                assertThat(production.at("user:op-1", kit.tenant, it, "x")).describedAs(it.name).isEmpty()
                assertThat(production.at("user:op-1", kit.tenant, it)).describedAs(it.name).isEmpty()
            }
            // no tenant grant gives nothing at TENANT; a scope reference at INSTALLATION is not the installation
            val tenantRef = kit.tenant.toString()
            assertThat(production.at("user:op-1", kit.tenant, ScopeLevel.TENANT, tenantRef)).isEmpty()
            assertThat(production.at("user:op-1", kit.tenant, ScopeLevel.INSTALLATION, tenantRef)).isEmpty()
        }

    @Test
    fun `the empty list starts and gives every subject the empty set`() =
        run(arrayOf("access.installation-roles=")) { kit ->
            val production = kit.context.getBean(PolicyRoleResolver::class.java)
            assertThat(production.at("user:op-1", kit.tenant)).isEmpty()
        }

    @Test
    fun `probe the property absent refuses the start naming it`() {
        val failure = refusedStart(null)
        assertThat(failure).isNotNull()
        assertThat(generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.toList().joinToString(" "))
            .contains("access.installation-roles")
    }

    @Test
    fun `probe an invalid entry refuses the start`() {
        fun entry(
            subject: String,
            role: String,
        ) = arrayOf("access.installation-roles[0].subject=$subject", "access.installation-roles[0].role=$role")
        listOf(
            entry("user:x", "tenant.admin"),
            entry("user:x", "platform.engineer2"),
            entry("user:x", "Platform.Engineer"),
            entry("user:x", ""),
            entry(" ", "platform.engineer"),
            entry("", "deployment.operator"),
            arrayOf("access.installation-roles[0].role=deployment.operator"),
            arrayOf("access.installation-roles[0].subject=user:x"),
        ).forEach { roles -> assertThat(refusedStart(roles)).describedAs(roles.joinToString()).isNotNull() }
        // the test property parser trims a trailing space, so these look-alikes are asked of the entry itself
        listOf("platform.engineer ", " deployment.operator", "PLATFORM.ENGINEER").forEach {
            assertThatThrownBy { InstallationRole.of("user:x", it) }
                .describedAs("[$it]")
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(InstallationRole.of("user:x", "platform.engineer").role).isEqualTo(Role.PLATFORM_ENGINEER)
    }
}
