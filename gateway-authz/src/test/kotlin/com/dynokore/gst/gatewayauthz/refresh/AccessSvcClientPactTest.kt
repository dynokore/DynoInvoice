package com.dynokore.gst.gatewayauthz.refresh

import au.com.dius.pact.consumer.ConsumerPactBuilder
import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslRequestWithPath
import au.com.dius.pact.consumer.dsl.PactDslResponse
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private const val CONSUMER = "api-gateway"
private const val PROVIDER = "access-svc"
private const val KEYS_PATH = "/internal/v1/access/verification-keys"
private const val REVOCATIONS_PATH = "/internal/v1/access/revocations"
private val EXPECTED = listOf("getVerificationKeys" to KEYS_PATH, "getRevocationList" to REVOCATIONS_PATH)
private val PUBLIC_KEY: String = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

/** The two interactions of the edge, with the field names of platform-contracts `access/openapi.yaml`. */
private object EdgeInteractions {
    fun verificationKeys(request: PactDslRequestWithPath): PactDslResponse =
        request
            .method("GET")
            .willRespondWith()
            .status(200)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(
                PactDslJsonBody().also { body ->
                    body
                        .minArrayLike("keys", 1)
                        .stringType("kid", "2026-09")
                        .stringType("alg", "RS256")
                        .stringType("publicKey", PUBLIC_KEY)
                        .closeObject()
                        ?.closeArray()
                },
            )

    fun revocationList(request: PactDslRequestWithPath): PactDslResponse =
        request
            .method("GET")
            .query("limit=500")
            .willRespondWith()
            .status(200)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(
                PactDslJsonBody().also { body ->
                    body
                        .integerType("listVersion", 42)
                        .booleanType("snapshot", true)
                        .stringType("nextCursor", "c1")
                    body
                        .minArrayLike("items", 1)
                        .uuid("entryId", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                        .integerType("version", 41)
                        .stringValue("kind", "PRINCIPAL")
                        .stringType("principalRef", "erp-ingest-01")
                        .stringType("revokedAt", "2026-10-12T08:00:00Z")
                        .stringType("expiresAt", "2026-10-12T08:15:00Z")
                        .closeObject()
                        ?.closeArray()
                },
            )

    /** Both interactions in one pact, as the file `build/pacts/<consumer>-<provider>.json` holds them. */
    fun edgePact(consumer: String): V4Pact {
        val provider = ConsumerPactBuilder.consumer(consumer).hasPactWith(PROVIDER)
        val keys = verificationKeys(provider.uponReceiving("getVerificationKeys").path(KEYS_PATH))
        return revocationList(keys.uponReceiving("getRevocationList").path(REVOCATIONS_PATH))
            .toPact(V4Pact::class.java)
    }

    fun interactionsOf(pact: V4Pact) =
        pact.interactions.map { it.description to it.asSynchronousRequestResponse()!!.request.path }

    fun assertEdgeNames(pact: V4Pact) {
        if (pact.consumer.name != CONSUMER || pact.provider.name != PROVIDER) {
            throw AssertionError("pact names are ${pact.consumer.name} -> ${pact.provider.name}")
        }
    }
}

/**
 * The consumer pact of the `API gateway -> access-svc` edge (sync-edges.yaml; INV-REQ-1225 b1, STK-40): the two
 * interactions of [AccessSvcClient], getVerificationKeys and getRevocationList, driven through the real client. The
 * pact is written to `build/pacts/api-gateway-access-svc.json` and published by `pactPublish`; access-svc verifies it
 * in its own build.
 */
@ExtendWith(WrittenPactFileCheck::class, PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4)
class AccessSvcClientPactTest {
    private fun client(server: MockServer) = AccessSvcClient(RestClient.builder().baseUrl(server.getUrl()).build())

    @Pact(consumer = CONSUMER)
    fun getVerificationKeys(builder: PactDslWithProvider): V4Pact =
        EdgeInteractions
            .verificationKeys(builder.uponReceiving("getVerificationKeys").path(KEYS_PATH))
            .toPact(V4Pact::class.java)

    @Pact(consumer = CONSUMER)
    fun getRevocationList(builder: PactDslWithProvider): V4Pact =
        EdgeInteractions
            .revocationList(builder.uponReceiving("getRevocationList").path(REVOCATIONS_PATH))
            .toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "getVerificationKeys")
    fun `getVerificationKeys is read through the client`(server: MockServer) {
        val key = client(server).verificationKeys().single()
        key.kid shouldBe "2026-09"
        key.alg shouldBe "RS256"
        key.publicKey.toList() shouldBe Base64.getDecoder().decode(PUBLIC_KEY).toList()
    }

    @Test
    @PactTestFor(pactMethod = "getRevocationList")
    fun `getRevocationList is read through the client`(server: MockServer) {
        val page = client(server).revocations(null, null)
        page.listVersion shouldBe 42
        page.snapshot shouldBe true
        page.items.single().principalRef shouldBe "erp-ingest-01"
        page.nextCursor shouldBe "c1"
    }

    @Test
    @PactTestFor(pactMethod = "getVerificationKeys")
    fun `a client that reads publicKey as key fails against the pact`(server: MockServer) {
        val body =
            RestClient
                .builder()
                .baseUrl(server.getUrl())
                .build()
                .get()
                .uri(KEYS_PATH)
                .retrieve()
                .body(String::class.java)
        assertThrows<Exception> {
            JsonMapper
                .builder()
                .findAndAddModules()
                .build()
                .readValue(body, KeyReadingAsKey::class.java)
        }
    }

    private class KeyReadingAsKey(
        val keys: List<Entry>,
    ) {
        class Entry(
            val kid: String,
            val alg: String,
            val key: ByteArray,
        )
    }
}

/**
 * Runs after [PactConsumerTestExt] (JUnit calls after-all callbacks in reverse registration order, and this one is
 * registered first), when the pact file is written: it holds exactly the edge's two interactions and names.
 */
class WrittenPactFileCheck : AfterAllCallback {
    override fun afterAll(context: ExtensionContext) {
        val dir = requireNotNull(System.getProperty("pact.rootDir")) { "gst.pact sets pact.rootDir" }
        val json = JsonMapper.builder().build().readTree(Files.readString(Path.of(dir, "api-gateway-access-svc.json")))
        json["consumer"]["name"].asString() shouldBe CONSUMER
        json["provider"]["name"].asString() shouldBe PROVIDER
        json["interactions"].size() shouldBe EXPECTED.size
        json["interactions"]
            .values()
            .map {
                it["description"].asString() to it["request"]["path"].asString()
            }.toSet() shouldBe
            EXPECTED.toSet()
    }
}

/** Probes of the pact definition itself: no mock server, so no interaction is expected to be received. */
class AccessSvcEdgePactModelTest {
    @Test
    fun `the pact holds the two interactions of the edge and none for the singular path`() {
        val pact = EdgeInteractions.edgePact(CONSUMER)
        EdgeInteractions.assertEdgeNames(pact)
        EdgeInteractions.interactionsOf(pact) shouldBe EXPECTED
        EdgeInteractions.interactionsOf(pact).none { it.second == "/internal/v1/access/verification-key" } shouldBe true
    }

    @Test
    fun `a pact whose consumer is gateway-authz fails the name check`() {
        assertThrows<AssertionError> { EdgeInteractions.assertEdgeNames(EdgeInteractions.edgePact("gateway-authz")) }
    }

    @Test
    fun `an interaction for the singular path is not the edge's interaction`() {
        val wrong =
            ConsumerPactBuilder
                .consumer(CONSUMER)
                .hasPactWith(PROVIDER)
                .uponReceiving("getVerificationKeys")
                .path("/internal/v1/access/verification-key")
                .method("GET")
                .willRespondWith()
                .status(200)
                .toPact(V4Pact::class.java)
        (EdgeInteractions.interactionsOf(wrong) == EXPECTED) shouldBe false
    }
}
