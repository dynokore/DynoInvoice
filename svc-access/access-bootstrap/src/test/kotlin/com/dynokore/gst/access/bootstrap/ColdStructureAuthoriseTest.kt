package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.DegradedAllowTable
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.OperationKey
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.replica.guardedBy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.actuate.endpoint.ApiVersion
import org.springframework.boot.actuate.endpoint.SecurityContext
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.client.MockMvcClientHttpRequestFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * INV-AC-9089 (INV-Q-9081 A, INV-Q-17134 A, INV-REQ-1199): a cold access-svc answers 503 SRT-013 and is not ready until
 * its structure replica holds its snapshot; afterwards an unknown entity is a definite DENY NO_ENTITY.
 */
class ColdStructureAuthoriseTest {
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val caller = "IDN-COLD"

    private fun cold(block: (AssertableWebApplicationContext, MockMvc) -> Unit) {
        BoundaryFixture.runner().withPropertyValues("access.test.cold-structure=true").run { context ->
            context.startupFailure?.let { throw it }
            // what the application publishes once it has started: the readiness state member of the ready group is UP
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC)
            Seeds.grant(context, tenant, SeedSpec(caller, Role.TAX_APPROVER, Scope.Entity(EntityRef("ENT-LIVE"))))
            val mvc =
                MockMvcBuilders
                    .standaloneSetup(context.getBean(AuthoriseController::class.java))
                    .setControllerAdvice(ApiRefusalAdvice())
                    .build()
            block(context, mvc)
        }
    }

    private fun node(retired: Boolean) =
        StructureNode(StructureNodeKind.ENTITY, null, retired, "entity", null, emptyList(), 1)

    private fun replay(
        context: AssertableWebApplicationContext,
        ref: String,
        retired: Boolean,
        change: StructureChange,
    ) = context
        .getBean(StructureReplica::class.java)
        .accept(OrganisationStructureChanged(tenant, ref, change, node(retired)))

    private fun ask(
        context: AssertableWebApplicationContext,
        mvc: MockMvc,
        entity: String,
        permission: String = "pack.approve",
    ): MvcResult =
        context.getBean(TenantContext::class.java).bind(tenant, caller) {
            mvc
                .perform(
                    get("/internal/v1/access/authorise")
                        .param("principalRef", caller)
                        .param("permission", permission)
                        .param("scope", "entity:$entity"),
                ).andReturn()
        }

    private fun json(result: MvcResult) = JsonMapper.builder().build().readTree(result.response.contentAsString)

    private fun ready(context: AssertableWebApplicationContext): Status =
        context.getBean("structureReplicaHealthIndicator", HealthIndicator::class.java).health()!!.status

    /** The HTTP status of `GET /health/ready` on the management port (health endpoint web extension, ready group). */
    private fun readyHttp(context: AssertableWebApplicationContext): Int =
        context
            .getBean(HealthEndpointWebExtension::class.java)
            .health(ApiVersion.V3, WebServerNamespace.MANAGEMENT, SecurityContext.NONE, true, "ready")
            .status

    private fun assertNotReady(result: MvcResult) {
        assertThat(result.response.status).isEqualTo(503)
        assertThat(result.response.contentType).startsWith("application/problem+json")
        val body = json(result)
        assertThat(body["code"].asString()).isEqualTo("SRT-013")
        assertThat(body["type"].asString()).isEqualTo("https://errors.invoice.internal/SRT-013")
        assertThat(body["severity"].asString()).isEqualTo("BLOCK")
        assertThat(body["disposition"].asString()).isEqualTo("TERMINAL")
        assertThat(body.has("detail")).isFalse()
        assertThat(result.response.getHeader("Retry-After")).isNull()
    }

    @Test
    fun `INV-AC-9089 a cold replica answers 503 SRT-013 for every entity, then decides after the sync`() =
        cold { context, mvc ->
            // the live and the retired entity are held, but the snapshot is not complete
            replay(context, "ENT-LIVE", false, StructureChange.CREATED)
            replay(context, "ENT-RETIRED", true, StructureChange.RETIRED)
            listOf("ENT-LIVE", "ENT-UNKNOWN", "ENT-RETIRED").forEach { assertNotReady(ask(context, mvc, it)) }
            assertThat(ready(context)).isEqualTo(Status.DOWN)
            assertThat(readyHttp(context)).isEqualTo(503)

            context.getBean(StructureReplica::class.java).onSnapshotComplete(3)

            val live = ask(context, mvc, "ENT-LIVE")
            assertThat(live.response.status).isEqualTo(200)
            assertThat(json(live)["decision"].asString()).isEqualTo("ALLOW")
            // look-alike: an unknown entity stays a DENY after the sync, never a 503
            val unknown = ask(context, mvc, "ENT-UNKNOWN")
            assertThat(unknown.response.status).isEqualTo(200)
            assertThat(json(unknown)["decision"].asString()).isEqualTo("DENY")
            assertThat(json(unknown)["reasonCode"].asString()).isEqualTo("NO_ENTITY")
            val retired = ask(context, mvc, "ENT-RETIRED")
            assertThat(retired.response.status).isEqualTo(200)
            assertThat(json(retired)["decision"].asString()).isEqualTo("DENY")
            assertThat(ready(context)).isEqualTo(Status.UP)
            assertThat(readyHttp(context)).isEqualTo(200)

            // a disconnect makes both 503 again
            context.getBean(StructureReplica::class.java).onDisconnected()
            assertThat(ready(context)).isEqualTo(Status.DOWN)
            assertThat(readyHttp(context)).isEqualTo(503)
            assertNotReady(ask(context, mvc, "ENT-LIVE"))
        }

    private fun fallbackOf(mvc: MockMvc) =
        AccessAuthorityFallback(
            AuthoriseApi(
                RestClient
                    .builder()
                    .requestFactory(MockMvcClientHttpRequestFactory(mvc))
                    .baseUrl("http://access")
                    .build(),
            ),
        )

    // the bare entity reference: the shipped library adds the `entity:` prefix itself (INV-T-19209)
    private val request =
        AuthorizationRequest(tenant, Principal(caller, PrincipalKind.HUMAN), "ENT-LIVE", "pack.approve")

    private fun <T> bound(
        context: AssertableWebApplicationContext,
        block: () -> T,
    ): T = context.getBean(TenantContext::class.java).bind(tenant, caller, block)

    private fun deadline(context: AssertableWebApplicationContext) =
        Deadline.after(Duration.ofSeconds(1), context.getBean(Clock::class.java))

    /** A cold AuthorityReplica over the fallback, behind the edge policy of ReplicaAutoConfiguration. */
    private fun coldReplica(
        context: AssertableWebApplicationContext,
        mvc: MockMvc,
    ): AuthorityReplica {
        // a machine type listed for the action, so the degraded rule applies to a machine principal
        val roles =
            OperationRoleMap {
                if (it ==
                    "pack.approve"
                ) {
                    mapOf("machine.SERVICE" to CellAllowance.PERMITTED)
                } else {
                    emptyMap()
                }
            }
        val edge = context.getBean(ResilienceRegistry::class.java).forEdge("access-svc")
        return AuthorityReplica(
            context.getBean(Clock::class.java),
            roles,
            fallback = fallbackOf(mvc).guardedBy(edge),
            degradedAllow = DegradedAllowTable(setOf("POST" to "/pack/approve")),
        )
    }

    private val operation get() = instantiate(OperationKey::class.java, "POST", "/pack/approve", "mapping.approve")

    @Test
    fun `INV-AC-9089 the AccessAuthorityFallback run against a cold access-svc gets no verdict`() =
        cold { context, mvc ->
            replay(context, "ENT-LIVE", false, StructureChange.CREATED)
            val failure = runCatching { bound(context) { fallbackOf(mvc).fetch(request, deadline(context)) } }
            assertThat(failure.isFailure).describedAs("fetch returned %s", failure.getOrNull()).isTrue()
            assertThat(failure.exceptionOrNull()).hasMessageContaining("503")
        }

    @Test
    fun `INV-AC-9089 a cold AuthorityReplica over the fallback denies a human and allows a machine`() =
        cold { context, mvc ->
            replay(context, "ENT-LIVE", false, StructureChange.CREATED)
            // the edge policy turns the failure into Unavailable, so the degraded rule applies
            val denied =
                bound(
                    context,
                ) { coldReplica(context, mvc).decide(request.copy(operation = operation), deadline(context)) }
            assertThat(denied.allowed).isFalse()
            assertThat(denied.source).isEqualTo(DecisionSource.DEGRADED)
            assertThat(denied.reason).isEqualTo(DecisionReason.AUTHORITY_UNAVAILABLE)
            val machine =
                instantiate(
                    Principal::class.java,
                    "MACHINE-1",
                    PrincipalKind.MACHINE,
                    tenant.value,
                    MachineEntityCoverage.Entity("ENT-LIVE"),
                    "mapping.approve",
                    "TOKEN-1",
                    "SERVICE",
                )
            val machineRequest = request.copy(principal = machine, operation = operation)
            val allowed = bound(context) { coldReplica(context, mvc).decide(machineRequest, deadline(context)) }
            assertThat(allowed.allowed).describedAs("%s", allowed).isTrue()
            assertThat(allowed.source).isEqualTo(DecisionSource.DEGRADED)
            assertThat(allowed.reason).isEqualTo(DecisionReason.MACHINE_FLOW_CONTINUES)
        }

    /** The library makes these two only from a verified token or route (internal constructors); the test stands in. */
    private fun <T> instantiate(
        type: Class<T>,
        vararg args: Any?,
    ): T =
        type.declaredConstructors
            .single { it.parameterCount == args.size && !it.isSynthetic }
            .also { it.isAccessible = true }
            .newInstance(*args)
            .let(type::cast)

    @Test
    fun `INV-AC-9089 the ready group gains structureReplica, live is unchanged, require-snapshot is true`() {
        val flat =
            YamlPropertiesFactoryBean()
                .apply { setResources(ClassPathResource("application.yaml")) }
                .`object`!!

        fun at(key: String): Any? = flat.getProperty(key)
        assertThat(at("management.endpoint.health.group.ready.include"))
            .isEqualTo("readinessState,vault,db,eventLoops,structureReplica")
        assertThat(at("management.endpoint.health.group.live.include")).isEqualTo("livenessState")
        assertThat(at("serviceruntime.replica.structure.require-snapshot")).isEqualTo("true")
    }
}
