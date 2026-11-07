package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.domain.registry.RegisterPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * INV-T-4946, the settings half (INV-Q-10167 A, INV-Q-16977 A): VP-29, VP-38 and VP-65 are written through the
 * `setPolicy` servlet that service-runtime mounts over the service's PolicyWriter, with a test `SetterPermission`
 * (service-runtime 12 decides by permission key, INV-Q-10027). A shorter value is accepted and stored; a lifetime above the default is
 * refused with its POL code and the stored value stays; AUTO_APPLY is stored for the entity it was set for.
 */
class RegistrySettingsTest {
    private val tenant = AtomicReference<TenantId>()

    private val entities = CopyOnWriteArrayList<EntityRef>()

    private val principals =
        EndUserPrincipalResolver { header ->
            val actor = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            actor?.takeIf { it.startsWith("IDN-") }?.let { EndUserPrincipal(it, tenant.get(), entities.toSet()) }
        }

    /** Who may set which setting (INV-Q-10027): decided by the permission key, as the service-runtime port asks. */
    private val setters =
        SetterPermission { actor, _, _, _, _, key ->
            when (actor.principal) {
                "IDN-INTEGRATOR" -> key == "vp-38.set"
                "IDN-ADVISOR" -> key in setOf("vp-65.set", "vp-29.set")
                else -> false
            }
        }

    private fun run(block: (RegistryEnv) -> Unit) =
        runRegistry(
            customize = {
                it
                    .withBean(EndUserPrincipalResolver::class.java, { principals })
                    .withBean(SetterPermission::class.java, { setters })
            },
        ) { env ->
            tenant.set(env.ce.tenant)
            block(env)
        }

    private fun RegistryEnv.setPolicy(
        actor: String,
        vp: String,
        level: String,
        selected: String,
        entity: String? = null,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/$vp")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $actor")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val scope = entity ?: ce.tenant.toString()
        val entityRef = entity?.let { ""","entityRef":"$it"""" }.orEmpty()
        request.setContent(
            (
                """{"scopeLevel":"$level","scopeRef":"$scope"$entityRef,"selected":[$selected],""" +
                    """"effectiveDate":"${businessDate()}","reason":"INV-T-4946 settings test"}"""
            ).toByteArray(),
        )
        val response = MockHttpServletResponse()
        val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                .servlet as HttpServlet
        servlet.service(request, response)
        return response
    }

    /** The option key and the value JSON that [PolicyReader.resolve] gives for [vp] on the business date. */
    private fun RegistryEnv.stored(
        vp: String,
        scope: PolicyScope,
    ): Pair<String, String?> =
        ce.inTenant {
            context.getBean(TransactionRunner::class.java).inTransaction {
                val option =
                    context
                        .getBean(PolicyReader::class.java)
                        .resolve(VariationPointKey(vp), scope, businessDate())
                        .selected
                        .single()
                option.key to option.valueJson
            }
        }

    private fun RegistryEnv.tenantScope() = PolicyScope.tenant(ce.tenant)

    private fun shorter(
        first: String,
        a: Int,
        second: String,
        b: Int,
    ) = """{"option":"SHORTER","value":{"$first":$a,"$second":$b}}"""

    @Test
    fun `INV-AC-2285 a shorter directory lifetime is accepted, stored and applied`() =
        run { env ->
            val entity = env.entity()
            val set = env.setPolicy("IDN-INTEGRATOR", "VP-38", "TENANT", shorter("positive", 60, "negative", 10))
            assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
            val (key, value) = env.stored("VP-38", env.tenantScope())
            assertThat(key).isEqualTo("SHORTER")
            assertThat(value).contains("60").contains("10")
            env.reach(entity, Simulators.PEPPOL_FOUND)
            val calls = Simulators.directory.calls()
            env.advance(Duration.ofMinutes(59))
            env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(Simulators.directory.calls()).isEqualTo(calls)
            env.advance(Duration.ofMinutes(2))
            env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(Simulators.directory.calls()).isEqualTo(calls + 1)
        }

    @Test
    fun `INV-AC-2314 a shorter register lifetime is accepted, stored and applied`() =
        run { env ->
            val entity = env.entity()
            val set = env.setPolicy("IDN-ADVISOR", "VP-65", "TENANT", shorter("found", 120, "not_found", 10))
            assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
            assertThat(env.stored("VP-65", env.tenantScope()).first).isEqualTo("SHORTER")
            env.lookup(entity, Simulators.UEN_REGISTERED)
            val calls = Simulators.register.calls()
            env.advance(Duration.ofMinutes(119))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(Simulators.register.calls()).isEqualTo(calls)
            env.advance(Duration.ofMinutes(2))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(Simulators.register.calls()).isEqualTo(calls + 1)
        }

    @Test
    fun `INV-AC-2033 INV-AC-2389 a lifetime above the default is refused with POL-013`() =
        run { env ->
            val before38 = env.stored("VP-38", env.tenantScope())
            val before65 = env.stored("VP-65", env.tenantScope())
            assertThat(before38.first).isEqualTo("DEFAULT_24H_1H")
            // 48 hours positive (2880 minutes) against the 24 hour default
            val directory =
                env.setPolicy("IDN-INTEGRATOR", "VP-38", "TENANT", shorter("positive", 2880, "negative", 10))
            assertThat(directory.status).describedAs(directory.contentAsString).isEqualTo(422)
            assertThat(directory.contentAsString).contains("POL-013")
            val register = env.setPolicy("IDN-ADVISOR", "VP-65", "TENANT", shorter("found", 1440, "not_found", 120))
            assertThat(register.status).describedAs(register.contentAsString).isEqualTo(422)
            assertThat(register.contentAsString).contains("POL-013")
            assertThat(env.stored("VP-38", env.tenantScope())).isEqualTo(before38)
            assertThat(env.stored("VP-65", env.tenantScope())).isEqualTo(before65)
        }

    @Test
    fun `INV-AC-2285 a subject without the setting's role cannot change it`() =
        run { env ->
            val before = env.stored("VP-38", env.tenantScope())
            val response = env.setPolicy("IDN-OUTSIDER", "VP-38", "TENANT", shorter("positive", 60, "negative", 10))
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(403)
            assertThat(env.stored("VP-38", env.tenantScope())).isEqualTo(before)
        }

    @Test
    fun `INV-AC-2276 AUTO_APPLY set through setPolicy is stored for its entity only`() =
        run { env ->
            val entity = env.entity()
            entities += EntityRef(entity)
            val set = env.setPolicy("IDN-ADVISOR", "VP-29", "ENTITY", """{"option":"AUTO_APPLY"}""", entity)
            assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
            assertThat(env.stored("VP-29", PolicyScope.entity(env.ce.tenant, entity)).first)
                .isEqualTo(RegisterPolicy.AUTO_APPLY.name)
            val other = env.entity()
            assertThat(env.stored("VP-29", PolicyScope.entity(env.ce.tenant, other)).first)
                .isEqualTo(RegisterPolicy.SUGGEST_CONFIRM.name)
        }
}
