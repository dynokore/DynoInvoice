package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.aggregate.Classified
import com.dynokore.gst.canonical.application.aggregate.Refused
import com.dynokore.gst.canonical.application.aggregate.Unavailable
import com.dynokore.gst.canonical.domain.document.Direction
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
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The behaviour of [RulesLineClassifier] itself (INV-T-22853) against a JDK HttpServer stub: the request body, the
 * Refused and Unavailable mappings and the Classified answer. The registry runs the block inline and routes an open
 * breaker or a timeout to the callbacks it is told to.
 */
class RulesLineClassifierTest {
    private class Stub : AutoCloseable {
        val bodies = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()

        @Volatile
        var status = 200

        @Volatile
        var reply = ""
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { handle(it) }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            paths += exchange.requestURI.path
            bodies += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val bytes = reply.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }

        override fun close() = server.stop(0)
    }

    private enum class Mode { RUN, OPEN_BREAKER, TIMEOUT }

    private class Registry(
        private val mode: Mode = Mode.RUN,
    ) : ResilienceRegistry {
        val asked = CopyOnWriteArrayList<String>()

        override fun forEdge(edge: String): ResilientEdge {
            asked += edge
            return object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T =
                    when (mode) {
                        Mode.RUN -> block()
                        Mode.OPEN_BREAKER -> onCallNotPermitted()
                        Mode.TIMEOUT -> onTimeout()
                    }
            }
        }
    }

    private val stub = Stub()
    private val mapper =
        tools.jackson.databind.json.JsonMapper
            .builder()
            .build()

    @AfterEach
    fun stopStub() = stub.close()

    private fun classify(
        registry: Registry = Registry(),
        url: String = stub.url,
    ) = RulesLineClassifier(
        RestClient.builder(),
        registry,
        MockEnvironment().also { it.setProperty(RulesEdge.URL_PROPERTY, url) },
    ).classify("E-001", LocalDate.parse("2026-03-20"), "SR-STD", Direction.SALE)

    private fun answer(
        category: String? = "SR",
        rate: String? = "0.09",
        packs: String = """[{"packType":"SCOPE","version":"1.0.0"},{"packType":"MAPPING","version":"2.0.0"}]""",
    ) = buildString {
        append("""{"scopeVerdict":"IN_SCOPE"""")
        if (category != null) append(""","categoryCode":"$category"""")
        if (rate != null) append(""","rate":$rate""")
        append(""","packVersionsApplied":$packs}""")
    }

    @Test
    fun `INV-AC-22852 a 200 answer is Classified with the category, verdict, rate and one version per pack type`() {
        stub.reply = answer()
        classify() shouldBe
            Classified(
                categoryCode = "SR",
                lineVerdict = "IN_SCOPE",
                packVersions = mapOf("SCOPE" to "1.0.0", "MAPPING" to "2.0.0"),
                rate = BigDecimal("0.09"),
            )
    }

    @Test
    fun `INV-AC-22852 a null rate stays null in Classified`() {
        stub.reply = answer(rate = null)
        (classify() as Classified).rate shouldBe null
    }

    @Test
    fun `INV-AC-22852 the request carries entity, date, code and direction and no electedSchemes`() {
        stub.reply = answer()
        classify()
        stub.paths.single() shouldBe "/internal/v1/rules/documents/evaluations"
        val sent = mapper.readTree(stub.bodies.single())
        sent["entityRef"].asString() shouldBe "E-001"
        sent["transactionDate"].asString() shouldBe "2026-03-20"
        sent["internalTaxCode"].asString() shouldBe "SR-STD"
        sent["direction"].asString() shouldBe "SALE"
        sent.has("electedSchemes") shouldBe false
    }

    @Test
    fun `INV-AC-22852 a missing categoryCode is Refused`() {
        stub.reply = answer(category = null)
        classify() shouldBe Refused
    }

    @Test
    fun `INV-AC-22852 a 4xx answer is Refused`() {
        stub.status = 422
        stub.reply = "{}"
        classify() shouldBe Refused
    }

    @Test
    fun `INV-AC-22852 a 5xx answer is Unavailable`() {
        stub.status = 503
        stub.reply = "{}"
        classify() shouldBe Unavailable
    }

    @Test
    fun `INV-AC-22852 an empty 200 body is Unavailable`() {
        stub.reply = ""
        classify() shouldBe Unavailable
    }

    @Test
    fun `INV-AC-22852 an open breaker and a timeout are Unavailable and never reach the stub`() {
        classify(Registry(Mode.OPEN_BREAKER)) shouldBe Unavailable
        classify(Registry(Mode.TIMEOUT)) shouldBe Unavailable
        stub.bodies.size shouldBe 0
    }

    @Test
    fun `INV-AC-22852 an unreachable rules-svc is Unavailable`() {
        val dead = Stub().also { it.close() }
        classify(url = dead.url) shouldBe Unavailable
    }

    @Test
    fun `INV-AC-22852 two versions of one pack type are Unavailable`() {
        stub.reply =
            answer(packs = """[{"packType":"SCOPE","version":"1.0.0"},{"packType":"SCOPE","version":"1.1.0"}]""")
        classify() shouldBe Unavailable
    }

    @Test
    fun `INV-AC-22852 the classifier asks for the rules edge`() {
        stub.reply = answer()
        val registry = Registry()
        classify(registry)
        registry.asked.toList() shouldBe listOf(RulesEdge.EDGE)
    }
}
