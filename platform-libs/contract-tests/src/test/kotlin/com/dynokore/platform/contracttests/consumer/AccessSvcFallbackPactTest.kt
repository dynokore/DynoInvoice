package com.dynokore.platform.contracttests.consumer

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.productruntime.fallback.AccessAuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * product-runtime's consumer pact of the `Any → access-svc (fallback)` edge (sync-edges.yaml; INV-REQ-1201 b3):
 * `authorise` through the generated client and [AccessAuthorityFallback], as the authority replica calls it while
 * its lag is beyond its bound. access-svc verifies it in its own build (INV-REQ-1225 b2; INV-Q-3925).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = Contracts.ACCESS_SVC, pactVersion = PactSpecVersion.V4)
class AccessSvcFallbackPactTest {
    private val clock = Clock.fixed(Instant.parse(Contracts.TIME), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString(Contracts.TENANT))
    private val principal = Principal("P-1", PrincipalKind.HUMAN)

    private val deadline = { Deadline.after(Duration.ofMillis(500), clock) }

    private fun fallback(server: MockServer): AccessAuthorityFallback {
        val client = RestClient.builder().baseUrl(server.getUrl()).build()
        return AccessAuthorityFallback(AuthoriseApi(client))
    }

    private fun decision(
        builder: PactDslWithProvider,
        state: String,
        permission: String,
        body: PactDslJsonBody,
    ): V4Pact = builder
        .given(state)
        .uponReceiving("authorise P-1 for $permission on E-001")
        .path(PATH)
        .method("GET")
        .query("principalRef=P-1&permission=$permission&scope=E-001")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(body)
        .toPact(V4Pact::class.java)

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun allowed(builder: PactDslWithProvider): V4Pact = decision(builder, "holds", "document.read", ALLOW)

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun denied(builder: PactDslWithProvider): V4Pact = decision(builder, "holds no", "document.void", DENY)

    @Test
    @PactTestFor(pactMethod = "allowed")
    fun `an ALLOW decision is a granted verdict`(server: MockServer) {
        val request = AuthorizationRequest(tenant, principal, "E-001", "document.read")
        fallback(server).fetch(request, deadline()) shouldBe FallbackResult.Found(AuthorityVerdict(true))
        Contracts.assertResponseConforms(OPENAPI, PATH, 200, ALLOW.body.toString())
    }

    @Test
    @PactTestFor(pactMethod = "denied")
    fun `a DENY decision is a refused verdict`(server: MockServer) {
        val request = AuthorizationRequest(tenant, principal, "E-001", "document.void")
        fallback(server).fetch(request, deadline()) shouldBe FallbackResult.Found(AuthorityVerdict(false))
        Contracts.assertResponseConforms(OPENAPI, PATH, 200, DENY.body.toString())
    }

    private companion object {
        const val OPENAPI = "access/openapi.yaml"
        const val PATH = "/internal/v1/access/authorise"
        val ALLOW: PactDslJsonBody = PactDslJsonBody().stringValue("decision", "ALLOW")
        val DENY: PactDslJsonBody = PactDslJsonBody().stringValue("decision", "DENY")
    }
}
