package com.dynokore.platform.contracttests.consumer

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.dynokore.gst.productRuntimeTenancyClient.adapters.clients.api.EntitiesApi
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.productruntime.fallback.TenancyStructureFallback
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * product-runtime's consumer pact of the `Any → tenancy-svc (fallback)` edge (sync-edges.yaml; INV-REQ-1200 b2):
 * `getEntityStructure` through the generated client and [TenancyStructureFallback], exactly as the structure replica
 * calls it on a miss. tenancy-svc verifies it in its own build (INV-REQ-1225 b2; coordinator ruling INV-Q-3925).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = Contracts.TENANCY_SVC, pactVersion = PactSpecVersion.V4)
class TenancySvcFallbackPactTest {
    private val clock = Clock.fixed(Instant.parse(Contracts.TIME), ZoneOffset.UTC)
    private val key = { ref: String -> StructureKey(TenantId(UUID.fromString(Contracts.TENANT)), ref) }

    private val deadline = { Deadline.after(Duration.ofMillis(500), clock) }

    private fun fallback(server: MockServer): TenancyStructureFallback {
        val client = RestClient.builder().baseUrl(server.getUrl()).build()
        return TenancyStructureFallback(EntitiesApi(client))
    }

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun structureNode(builder: PactDslWithProvider): V4Pact =
        builder
            .given("structure node E-001 exists")
            .uponReceiving("getEntityStructure for a node the structure replica does not hold")
            .path("/internal/v1/tenancy/entities/E-001")
            .method("GET")
            .willRespondWith()
            .status(200)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(FOUND)
            .toPact(V4Pact::class.java)

    @Pact(consumer = Contracts.PRODUCT_RUNTIME)
    fun unknownNode(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no structure node E-404 exists")
            .uponReceiving("getEntityStructure for an unknown node")
            .path("/internal/v1/tenancy/entities/E-404")
            .method("GET")
            .willRespondWith()
            .status(404)
            .toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "structureNode")
    fun `the fallback reads the full node with its structure version`(server: MockServer) {
        val result = fallback(server).fetch(key("E-001"), deadline())
        val node = result.shouldBeInstanceOf<FallbackResult.Found<StructureNode>>().value
        node.structureVersion shouldBe 9
        node.parentRef shouldBe "G-1"
        node.name shouldBe "Acme Pte Ltd"
        node.profile.single().attribute shouldBe "gstRegistrationNumber"
        Contracts.assertResponseConforms("tenancy/openapi.yaml", "$PATH/E-001", 200, FOUND.body.toString())
    }

    @Test
    @PactTestFor(pactMethod = "unknownNode")
    fun `a 404 is a node the replica reads as absent`(server: MockServer) {
        fallback(server).fetch(key("E-404"), deadline()) shouldBe FallbackResult.NotFound
    }

    private companion object {
        const val PATH = "/internal/v1/tenancy/entities"

        /** The required response fields, and the node fields TenancyStructureFallback reads (code is optional). */
        val FOUND: PactDslJsonBody =
            PactDslJsonBody()
                .stringType("entityRef", "E-001")
                .stringType("legalName", "Acme Pte Ltd")
                .stringMatcher("lifecycleState", "^(LIVE|RETIRED)$", "LIVE")
                .`object`("node")
                .stringMatcher("kind", "^[A-Z_]+$", "ENTITY")
                .stringType("parentRef", "G-1")
                .booleanType("retired", false)
                .stringType("name", "Acme Pte Ltd")
                .stringMatcher("code", "^[A-Z0-9]{1,10}$", "ACME")
                .minArrayLike("profile", 1)
                .stringType("attribute", "gstRegistrationNumber")
                .stringType("value", "M90312345A")
                .stringMatcher("effectiveDate", Contracts.DATE, "2026-01-01")
                .closeObject()!!
                .closeArray()!!
                .let { it as PactDslJsonBody }
                .integerType("structureVersion", 9L)
                .closeObject() as PactDslJsonBody
    }
}
