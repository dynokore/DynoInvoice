package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.model.ReleaseHeldDocumentRequest
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenPropagationInterceptor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
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

    @Test
    fun `INV-AC-12290 the console-bff end-user operations of the canonical contract are exactly the five covered`() {
        consoleBffEndUserOperations() shouldBe
            setOf(
                "getDocument",
                "getDocumentVersion",
                "listPreparedReassignments",
                "confirmPeriodReassignment",
                "releaseHeldDocument",
            )
    }

    /** Operation blocks are read by line: `operationId` and the `x-*` members are siblings at one indent. */
    private fun consoleBffEndUserOperations(): Set<String> {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/canonical/openapi.yaml")) {
                "missing classpath resource gst/contracts/canonical/openapi.yaml"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        return endUserConsoleOperations(text)
    }

    @Test
    fun `INV-AC-12290 the set check reports a sixth console-bff end-user operation`() {
        val sixth =
            """
            |  /x:
            |    get:
            |      operationId: sixthOperation
            |      x-caller-auth: end-user
            |      x-sync-edge: console-bff->canonical-svc
            """.trimMargin()
        endUserConsoleOperations(sixth) shouldBe setOf("sixthOperation")
    }

    @Test
    fun `INV-AC-12290 a look-alike operation name is a different member of the set`() {
        val lookAlike =
            """
            |    a:
            |      operationId: releaseHeldDocuments
            |      x-caller-auth: end-user
            |      x-sync-edge: console-bff->canonical-svc
            """.trimMargin()
        (endUserConsoleOperations(lookAlike) == setOf("releaseHeldDocument")) shouldBe false
    }

    @Test
    fun `INV-AC-12290 an end-user operation of another edge or another caller auth is not in the set`() {
        val other =
            """
            |    a:
            |      operationId: onIngestionEdge
            |      x-caller-auth: end-user
            |      x-sync-edge: ingestion-svc->canonical-svc
            |    b:
            |      operationId: onWorkload
            |      x-caller-auth: workload
            |      x-sync-edge: console-bff->canonical-svc
            |    c:
            |      operationId: inList
            |      x-caller-auth: end-user
            |      x-sync-edge:
            |        - ingestion-svc->canonical-svc
            |        - console-bff->canonical-svc
            """.trimMargin()
        endUserConsoleOperations(other) shouldBe setOf("inList")
    }

    private fun endUserConsoleOperations(text: String): Set<String> {
        class Op(
            val id: String,
            var auth: String? = null,
            val edges: MutableList<String> = mutableListOf(),
        )
        val ops = mutableListOf<Op>()
        var listKey = false
        for (raw in text.lines()) {
            val line = raw.trim()
            val value = { key: String -> line.removePrefix("$key:").trim().trim('\'', '"') }
            when {
                line.startsWith("operationId:") -> {
                    ops += Op(value("operationId"))
                    listKey = false
                }

                ops.isEmpty() -> {}

                line.startsWith("x-caller-auth:") -> {
                    ops.last().auth = value("x-caller-auth")
                    listKey = false
                }

                line.startsWith("x-sync-edge:") -> {
                    val inline = value("x-sync-edge")
                    if (inline.isNotEmpty()) ops.last().edges += inline
                    listKey = inline.isEmpty()
                }

                listKey && line.startsWith("- ") -> {
                    ops.last().edges += line.removePrefix("- ").trim().trim('\'', '"')
                }

                else -> {
                    listKey = false
                }
            }
        }
        return ops
            .filter { op -> op.auth == "end-user" && op.edges.any { it.startsWith("console-bff->") } }
            .map { it.id }
            .toSet()
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
