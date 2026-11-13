package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.ReleaseHeldDocumentRequest
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenPropagationInterceptor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * INV-AC-12290 (INV-T-12292): console-bff forwards the end user's token on the five canonical-svc operations marked
 * end-user. The client is the generated one, the builder is the bean `internalRestClientBuilder` with the
 * interceptor service-runtime contributes, and the server is a stub HTTP server of canonical-svc.
 */
class CanonicalEdgeClientTest {
    private class Seen(
        val path: String,
        val headers: Map<String, List<String>>,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        exchange.requestBody.readAllBytes()
        seen +=
            Seen(
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() },
            )
        // The proof is the request. releaseHeldDocument is answered with a ReleasedDocument; the other operations
        // get a 404, which the generated client reports as a failure after the request was sent.
        if (exchange.requestURI.path.endsWith("/release")) {
            val bytes = released.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } else {
            exchange.sendResponseHeaders(404, -1)
        }
        exchange.close()
    }

    private val docUuid = UUID.randomUUID()
    private val reassignmentId = UUID.randomUUID()
    private val released =
        """{"docUuid":"$docUuid","holdCode":"ING-017","releasedBy":"user-1","releasedAt":"2026-11-13T10:00:00Z"}"""

    private val operations: Map<String, (CanonicalEdgeClient) -> Any?> =
        mapOf(
            "getDocument" to { c -> c.getDocument(docUuid) },
            "getDocumentVersion" to { c -> c.getDocumentVersion(docUuid, 1) },
            "listPreparedReassignments" to { c -> c.listPreparedReassignments("ENT-1") },
            "confirmPeriodReassignment" to { c -> c.confirmPeriodReassignment("ENT-1", reassignmentId) },
            "releaseHeldDocument" to { c ->
                c.releaseHeldDocument(docUuid, ReleaseHeldDocumentRequest(ReleaseHeldDocumentRequest.HoldCode.ING_017))
            },
        )

    /** The context is built as the service builds it: the auto-configuration of the builder, the interceptor beans. */
    private fun context(extra: List<ClientHttpRequestInterceptor>): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(
            MapPropertySource("test", mapOf(CanonicalEdgeClient.URL_PROPERTY to url)),
        )
        context.register(InternalRestClientAutoConfiguration::class.java)
        extra.forEachIndexed { i, interceptor ->
            context.registerBean("interceptor$i", ClientHttpRequestInterceptor::class.java, Supplier { interceptor })
        }
        context.register(CanonicalEdgeClient::class.java)
        context.refresh()
        return context
    }

    private fun withClient(block: (CanonicalEdgeClient) -> Unit) =
        context(listOf(EndUserTokenPropagationInterceptor())).use { block(it.getBean(CanonicalEdgeClient::class.java)) }

    /** The call outcome is irrelevant: the stub answers 404 and the generated client throws; the request was sent. */
    private fun call(op: () -> Any?) {
        runCatching { op() }
    }

    private fun authorization(request: Seen): List<String>? = request.headers["authorization"]

    @Test
    fun `INV-AC-12290 each of the five operations carries the ambient end-user token`() =
        withClient { client ->
            for ((name, op) in operations) {
                seen.clear()
                CurrentEndUserToken.callWith("Bearer end-user-1") { call { op(client) } }
                withClue(name) { seen.size shouldBe 1 }
                withClue(name) { authorization(seen.single()) shouldBe listOf("Bearer end-user-1") }
            }
        }

    @Test
    fun `INV-AC-12290 two calls in sequence each carry their own token`() =
        withClient { client ->
            for ((name, op) in operations) {
                seen.clear()
                CurrentEndUserToken.callWith("Bearer end-user-1") { call { op(client) } }
                CurrentEndUserToken.callWith("Bearer end-user-2") { call { op(client) } }
                withClue(name) {
                    seen.map { authorization(it) } shouldContainExactly
                        listOf(listOf("Bearer end-user-1"), listOf("Bearer end-user-2"))
                }
            }
        }

    @Test
    fun `INV-AC-12290 a call with no ambient token carries no authorization header`() =
        withClient { client ->
            for ((name, op) in operations) {
                seen.clear()
                call { op(client) }
                withClue(name) { seen.size shouldBe 1 }
                withClue(name) { authorization(seen.single()) shouldBe null }
                withClue(name) {
                    seen
                        .single()
                        .headers.keys
                        .filter { it.startsWith("author") } shouldBe emptyList()
                }
            }
        }

    private val five =
        setOf(
            "getDocument",
            "getDocumentVersion",
            "listPreparedReassignments",
            "confirmPeriodReassignment",
            "releaseHeldDocument",
        )

    private val contractText: String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/canonical/openapi.yaml")) {
            "missing classpath resource gst/contracts/canonical/openapi.yaml"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `INV-AC-12290 the console-bff end-user operations of the canonical contract are exactly the five covered`() {
        endUserConsoleOperations(contractText) shouldBe five
    }

    /** A copy of the pinned contract with [extraOperation] added as one more path item. */
    private fun withPath(extraOperation: String): String {
        val copy = contractText.replaceFirst("\npaths:\n", "\npaths:\n$extraOperation\n")
        check(copy != contractText) { "the contract has no top-level paths: line to extend" }
        return copy
    }

    private fun sixthOperation(name: String) =
        "  /internal/v1/canonical/probe-$name:\n    post:\n      operationId: $name\n" +
            "      x-caller-auth: end-user\n      x-sync-edge: console-bff->canonical-svc\n" +
            "      responses:\n        '200':\n          description: probe"

    @Test
    fun `INV-AC-12290 probe a copy of the contract with a sixth console-bff end-user operation fails the set test`() {
        (endUserConsoleOperations(withPath(sixthOperation("sixthOperation"))) == five) shouldBe false
        endUserConsoleOperations(withPath(sixthOperation("sixthOperation"))) shouldBe five + "sixthOperation"
    }

    @Test
    fun `INV-AC-12290 probe a copy of the contract without releaseHeldDocument fails the set test`() {
        val without = contractText.replace("operationId: releaseHeldDocument", "operationId: releaseHeldDocumentGone")
        (endUserConsoleOperations(without) == five) shouldBe false
        endUserConsoleOperations(without) shouldBe five - "releaseHeldDocument" + "releaseHeldDocumentGone"
        val removed =
            contractText.replace(
                "x-caller-auth: end-user\n      x-sync-edge: console-bff->canonical-svc",
                "x-caller-auth: workload",
            )
        (endUserConsoleOperations(removed) == five) shouldBe false
    }

    @Test
    fun `INV-AC-12290 probe a copy of the contract with the look-alike releaseHeldDocuments fails the set test`() {
        val lookAlike = contractText.replace("operationId: releaseHeldDocument", "operationId: releaseHeldDocuments")
        (endUserConsoleOperations(lookAlike) == five) shouldBe false
        endUserConsoleOperations(lookAlike) shouldBe five - "releaseHeldDocument" + "releaseHeldDocuments"
    }

    @Test
    fun `INV-AC-12290 the markers ahead of operationId still belong to their own operation`() {
        val reordered =
            """
            paths:
              /a:
                post:
                  x-caller-auth: end-user
                  x-sync-edge: console-bff->canonical-svc
                  operationId: markersFirst
              /b:
                get:
                  operationId: markersLast
                  x-sync-edge:
                    - ingestion-svc->canonical-svc
                    - console-bff->canonical-svc
                  x-caller-auth: end-user
            """.trimIndent()
        endUserConsoleOperations(reordered) shouldBe setOf("markersFirst", "markersLast")
    }

    @Test
    fun `INV-AC-12290 an end-user operation of another edge or another caller auth is not in the set`() {
        val other =
            """
            paths:
              /a:
                get:
                  operationId: onIngestionEdge
                  x-caller-auth: end-user
                  x-sync-edge: ingestion-svc->canonical-svc
              /b:
                get:
                  operationId: onWorkload
                  x-caller-auth: workload
                  x-sync-edge: console-bff->canonical-svc
              /c:
                get:
                  operationId: noEdge
                  x-caller-auth: end-user
            """.trimIndent()
        endUserConsoleOperations(other) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-12290 a shape the check does not know is a failure, not a skipped operation`() {
        val unknownEdge =
            "paths:\n  /a:\n    get:\n      operationId: x\n      x-caller-auth: end-user\n      x-sync-edge: {a: b}\n"
        shouldThrow<IllegalStateException> { endUserConsoleOperations(unknownEdge) }
        val noId = "paths:\n  /a:\n    get:\n      x-caller-auth: end-user\n      x-sync-edge: console-bff->x\n"
        shouldThrow<IllegalStateException> { endUserConsoleOperations(noId) }
        shouldThrow<IllegalStateException> { endUserConsoleOperations("paths:\n  /a:\n    \$ref: other.yaml\n") }
    }

    /** The YAML document is parsed (not scanned by line): `paths.*.<method>` members, any key order. */
    private fun endUserConsoleOperations(text: String): Set<String> {
        val root = YamlMapFactoryBean().apply { setResources(ByteArrayResource(text.toByteArray())) }.getObject()
        val paths = checkNotNull(root?.get("paths") as? Map<*, *>) { "the contract has no paths map" }
        val found = mutableSetOf<String>()
        for ((path, item) in paths) {
            val operations = checkNotNull(item as? Map<*, *>) { "path item $path is not a map" }
            check(!operations.containsKey("\$ref")) { "path item $path is a \$ref, which this check does not follow" }
            for ((method, op) in operations) {
                if (method !in HTTP_METHODS) continue
                val members = checkNotNull(op as? Map<*, *>) { "$method $path is not a map" }
                val id = checkNotNull(members["operationId"] as? String) { "$method $path has no operationId" }
                val edges =
                    when (val edge = members["x-sync-edge"]) {
                        null -> {
                            emptyList()
                        }

                        is String -> {
                            listOf(edge)
                        }

                        is List<*> -> {
                            edge.map {
                                checkNotNull(
                                    it as? String,
                                ) { "$id: x-sync-edge element is not a string" }
                            }
                        }

                        else -> {
                            error("$id: x-sync-edge is neither a string nor a list")
                        }
                    }
                if (members["x-caller-auth"] == "end-user" && edges.any { it.startsWith("console-bff->") }) found += id
            }
        }
        return found
    }

    private companion object {
        val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")
    }

    private fun <T> withClue(
        clue: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$clue: ${e.message}", e)
        }
}
