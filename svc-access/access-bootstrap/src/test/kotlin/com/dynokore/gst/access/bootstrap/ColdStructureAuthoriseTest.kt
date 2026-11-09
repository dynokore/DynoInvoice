package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
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

            // a disconnect makes both 503 again
            context.getBean(StructureReplica::class.java).onDisconnected()
            assertThat(ready(context)).isEqualTo(Status.DOWN)
            assertNotReady(ask(context, mvc, "ENT-LIVE"))
        }

    @Test
    fun `INV-AC-9089 the AccessAuthorityFallback run against a cold access-svc gets no verdict`() =
        cold { context, mvc ->
            replay(context, "ENT-LIVE", false, StructureChange.CREATED)
            val client =
                RestClient
                    .builder()
                    .requestFactory(MockMvcClientHttpRequestFactory(mvc))
                    .baseUrl("http://access")
                    .build()
            val fallback = AccessAuthorityFallback(AuthoriseApi(client))
            // the entity ref carries the contract's `entity:` form: the fallback sends the request's entityRef as the
            // scope unchanged, and a bare ref is answered DENY BAD_SCOPE before any structure read
            val human = Principal(caller, PrincipalKind.HUMAN)
            val request = AuthorizationRequest(tenant, human, "entity:ENT-LIVE", "pack.approve")
            val deadline = Deadline.after(Duration.ofSeconds(1), context.getBean(Clock::class.java))
            // the edge policy of service-runtime turns any failure of the call into Unavailable, so the replica
            // applies its degraded rule (a human action is denied, a machine action allowed)
            val failure =
                runCatching {
                    context.getBean(TenantContext::class.java).bind(tenant, caller) {
                        fallback.fetch(request, deadline)
                    }
                }
            assertThat(failure.isFailure).describedAs("fetch returned %s", failure.getOrNull()).isTrue()
            assertThat(failure.exceptionOrNull()).hasMessageContaining("503")
        }

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
