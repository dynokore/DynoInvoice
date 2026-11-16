package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.RulesAnswer.Refused
import com.dynokore.gst.canonical.adapters.clients.RulesAnswer.Unavailable
import com.dynokore.platform.serviceruntime.identity.FlowContext
import com.dynokore.platform.serviceruntime.identity.FlowContextIssuer
import com.dynokore.platform.serviceruntime.identity.FlowContextOperations
import com.dynokore.platform.serviceruntime.identity.FlowContextPropagationInterceptor
import com.dynokore.platform.serviceruntime.identity.FlowEntity
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-AC-23637 (INV-T-23638): the rules call carries the flow context the request holds, and 403 SRT-004 is
 * unavailable. The interceptor is the one service-runtime offers; the issuer is a test double (the context is only
 * ever propagated by the client, never made by it). The registry runs the block on another thread, as the real
 * edge does.
 */
class RulesFlowContextTest {
    private class Seen(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private class Stub : AutoCloseable {
        val seen = CopyOnWriteArrayList<Seen>()

        @Volatile
        var status = 200

        @Volatile
        var body = """{"scopeVerdict":"IN_SCOPE","documentTypeCode":"380"}"""
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { handle(it) }
                executor = this@Stub.executor
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            seen +=
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                )
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/problem+json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private class ThreadHoppingRegistry : ResilienceRegistry {
        private val executor = Executors.newVirtualThreadPerTaskExecutor()

        override fun forEdge(edge: String): ResilientEdge =
            object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = executor.submit<T> { block() }.get()
            }
    }

    private val stub = Stub()
    private val registry = ThreadHoppingRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC)

    // the issuer's output IS the compact form the context carries: the test knows it without reading the internal field
    private fun context(compact: String): FlowContext =
        FlowContext.begin(
            FlowContextIssuer { compact },
            clock,
            "tenant-1",
            FlowEntity.entity("E-001"),
            "MACHINE",
            "canonical-svc",
        )

    private val operations =
        FlowContextOperations.of(
            listOf(
                "test" to
                    "operationId\tmethod\tpath\nevaluateDocument\tPOST\t/internal/v1/rules/documents/evaluations\n",
            ),
        )

    private fun builder(): RestClient.Builder =
        RestClient.builder().requestInterceptor(FlowContextPropagationInterceptor(operations))

    private val client =
        RulesClient(
            builder(),
            registry,
            MockEnvironment().also { it.setProperty(RulesEdge.URL_PROPERTY, stub.url) },
            clock,
        )

    private val facts =
        EvaluationFacts("E-001", LocalDate.parse("2026-03-20"), "SR-STD", listOf("ARCS"), null)

    @AfterEach
    fun stopStub() = stub.close()

    private fun flowHeaders() = stub.seen.map { it.headers["x-flow-context"] }

    @Test
    fun `INV-AC-23637 a held context travels on the evaluation call as its compact form`() {
        FlowContext.runWith(context("header.payload.sig-A")) { client.evaluate(facts) }
        stub.seen.single().path shouldBe "/internal/v1/rules/documents/evaluations"
        flowHeaders() shouldBe listOf("header.payload.sig-A")
    }

    @Test
    fun `INV-AC-23637 no held context sends no flow context header`() {
        client.evaluate(facts)
        stub.seen.single().headers.containsKey("x-flow-context") shouldBe false
    }

    @Test
    fun `INV-AC-23637 a hand-set header on a path the list does not name is removed`() {
        val plain = builder().baseUrl(stub.url).build()
        FlowContext.runWith(context("header.payload.sig-A")) {
            plain
                .get()
                .uri("/internal/v1/rules/other")
                .header(FlowContext.HEADER_NAME, "forged")
                .retrieve()
                .toBodilessEntity()
        }
        stub.seen.single().path shouldBe "/internal/v1/rules/other"
        stub.seen.single().headers.containsKey("x-flow-context") shouldBe false
    }

    @Test
    fun `INV-AC-23637 two different held contexts in turn give two different headers`() {
        FlowContext.runWith(context("header.payload.sig-A")) { client.evaluate(facts) }
        FlowContext.runWith(context("header.payload.sig-B")) { client.evaluate(facts) }
        flowHeaders() shouldBe listOf("header.payload.sig-A", "header.payload.sig-B")
    }

    private fun answer(
        status: Int,
        body: String,
    ): RulesAnswer {
        stub.status = status
        stub.body = body
        return client.evaluate(facts)
    }

    private fun problem(
        status: Int,
        code: String,
    ) = """{"type":"about:blank","title":"t","status":$status,"code":"$code","correlationId":"c"}"""

    @Test
    fun `INV-AC-23637 403 SRT-004 is Unavailable`() {
        answer(403, problem(403, "SRT-004")) shouldBe Unavailable
    }

    @Test
    fun `INV-AC-23637 403 with another code is Refused 403 and look-alikes of the code are not Unavailable`() {
        answer(403, problem(403, "SRT-003")) shouldBe Refused(403)
        answer(403, problem(403, "SRT-040")) shouldBe Refused(403)
        answer(403, problem(403, "srt-004")) shouldBe Refused(403)
        answer(403, problem(403, "SRT-004 ")) shouldBe Refused(403)
        answer(403, problem(403, "XSRT-004")) shouldBe Refused(403)
        answer(403, "not json") shouldBe Refused(403)
        answer(403, """{"errors":[{"code":"SRT-004"}]}""") shouldBe Refused(403)
    }

    @Test
    fun `INV-AC-23637 a 400 is Refused 400 and SRT-004 on a 400 or a 503 keeps its status answer`() {
        answer(400, problem(400, "RUL-001")) shouldBe Refused(400)
        answer(400, problem(400, "SRT-004")) shouldBe Refused(400)
        answer(503, problem(503, "SRT-004")) shouldBe Unavailable
    }
}
