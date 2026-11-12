package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.RulesAnswer.Refused
import com.dynokore.gst.canonical.adapters.clients.RulesAnswer.Unavailable
import com.dynokore.gst.canonical.adapters.clients.RulesAnswer.Verdict
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The client of rules-svc on the edge canonical-svc->rules-svc (INV-T-21435) against a JDK HttpServer stub (stubs S1 to
 * S6 of INV-AC-21434): the builder is a plain RestClient.Builder, the environment a MockEnvironment and the registry a
 * fake that records the edge names it is asked for and enforces the deadline it is given.
 */
class RulesClientTest {
    private class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )

    private class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    private class Stub : AutoCloseable {
        val seen = CopyOnWriteArrayList<Seen>()

        @Volatile
        var reply = Reply(500)
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
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                )
            val answer = reply
            if (answer.delayMillis > 0) Thread.sleep(answer.delayMillis)
            val bytes = answer.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            runCatching {
                exchange.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /** Records the edge names asked for and the deadlines given; runs the block within the deadline it is given. */
    private class FakeRegistry(
        private val clock: Clock,
        private val enforceDeadline: Boolean = false,
    ) : ResilienceRegistry {
        val asked = CopyOnWriteArrayList<String>()
        val deadlines = CopyOnWriteArrayList<Deadline>()
        private val executor = Executors.newVirtualThreadPerTaskExecutor()

        override fun forEdge(edge: String): ResilientEdge {
            asked += edge
            return object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T {
                    deadlines += deadline
                    val future = executor.submit<T> { block() }
                    return try {
                        if (enforceDeadline) {
                            future.get(deadline.remaining(clock).toMillis(), TimeUnit.MILLISECONDS)
                        } else {
                            future.get()
                        }
                    } catch (_: TimeoutException) {
                        future.cancel(true)
                        onTimeout()
                    }
                }
            }
        }
    }

    private val stub = Stub()
    private val placeholder = "\${CANONICAL_RULES_SVC_URL}"
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-20T00:00:00Z"), ZoneOffset.UTC)

    @AfterEach
    fun stopStub() = stub.close()

    private fun environment(url: String?) =
        MockEnvironment().also { env -> url?.let { env.setProperty(RulesEdge.URL_PROPERTY, it) } }

    private fun client(
        registry: FakeRegistry,
        url: String = stub.url,
        clock: Clock = Clock.systemUTC(),
    ) = RulesClient(RestClient.builder(), registry, environment(url), clock)

    private val facts =
        EvaluationFacts(
            entityRef = "E-001",
            transactionDate = LocalDate.parse("2026-03-20"),
            internalTaxCode = "SR-STD",
            electedSchemes = listOf("ARCS"),
            counterpartySchemeData = SchemeData(listOf("ARCS"), arcsGstChargeAgreed = true),
        )

    private val mapper =
        tools.jackson.databind.json.JsonMapper
            .builder()
            .build()

    private fun strings(node: tools.jackson.databind.JsonNode): List<String> =
        mapper.readValue(node.toString(), Array<String>::class.java).toList()

    @Test
    fun `INV-AC-21434 S1 sees one POST on the evaluations path with the facts and no election member`() {
        val client = client(FakeRegistry(fixedClock))
        stub.reply = Reply(200, """{"scopeVerdict":"IN_SCOPE","documentTypeCode":"380"}""")
        client.evaluate(facts)
        val post = stub.seen.single()
        post.method shouldBe "POST"
        post.path shouldBe "/internal/v1/rules/documents/evaluations"
        val sent = mapper.readTree(post.body)
        sent["entityRef"].asString() shouldBe "E-001"
        sent["transactionDate"].asString() shouldBe "2026-03-20"
        sent["internalTaxCode"].asString() shouldBe "SR-STD"
        strings(sent["electedSchemes"]) shouldBe listOf("ARCS")
        strings(sent["counterpartySchemeData"]["approvedSchemes"]) shouldBe listOf("ARCS")
        sent["counterpartySchemeData"]["arcsGstChargeAgreed"].asBoolean() shouldBe true
        sent.has("election") shouldBe false
    }

    @Test
    fun `INV-AC-21434 the answers of S1 and S2 are Verdict by equality`() {
        val client = client(FakeRegistry(fixedClock))
        stub.reply = Reply(200, """{"scopeVerdict":"IN_SCOPE","documentTypeCode":"380"}""")
        client.evaluate(facts) shouldBe Verdict("IN_SCOPE", "380")
        stub.reply = Reply(200, """{"scopeVerdict":"SUBMITTABLE"}""")
        client.evaluate(facts) shouldBe Verdict("SUBMITTABLE", null)
    }

    @Test
    fun `INV-AC-21434 S3 answering 500 is Unavailable and not Refused`() {
        stub.reply = Reply(500, "{}")
        client(FakeRegistry(fixedClock)).evaluate(facts) shouldBe Unavailable
    }

    @Test
    fun `INV-AC-21434 S4 answering after 2 seconds is cut off at the 300 ms deadline and is Unavailable`() {
        stub.reply = Reply(200, """{"scopeVerdict":"IN_SCOPE"}""", delayMillis = 2000)
        val started = System.nanoTime()
        client(FakeRegistry(Clock.systemUTC(), enforceDeadline = true)).evaluate(facts) shouldBe Unavailable
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        (elapsed < Duration.ofMillis(1500)) shouldBe true
    }

    @Test
    fun `INV-AC-21434 S5 answering 400 is Refused(400) and not Unavailable`() {
        stub.reply = Reply(400, """{"type":"about:blank","title":"x","status":400,"code":"RUL-001"}""")
        val answer = client(FakeRegistry(fixedClock)).evaluate(facts)
        answer shouldBe Refused(400)
        (answer is Unavailable) shouldBe false
    }

    @Test
    fun `INV-AC-21434 S6 a closed port is Unavailable`() {
        val closed = ServerSocket(0).use { it.localPort }
        client(FakeRegistry(Clock.systemUTC()), "http://127.0.0.1:$closed").evaluate(facts) shouldBe Unavailable
    }

    @Test
    fun `INV-AC-21434 the edge name, the url property and the 300 ms deadline are the contract's`() {
        RulesEdge.EDGE shouldBe "canonical-svc->rules-svc"
        RulesEdge.URL_PROPERTY shouldBe "canonical.rules-svc-url"
        RulesEdge.DEADLINE shouldBe Duration.ofMillis(300)
        val registry = FakeRegistry(fixedClock)
        stub.reply = Reply(200, """{"scopeVerdict":"IN_SCOPE"}""")
        client(registry, clock = fixedClock).evaluate(facts)
        registry.asked shouldBe listOf("canonical-svc->rules-svc")
        registry.deadlines.map { it.remaining(fixedClock) } shouldBe listOf(Duration.ofMillis(300))
    }

    @Test
    fun `INV-AC-21434 a missing, blank or placeholder url property fails the construction`() {
        val registry = FakeRegistry(fixedClock)

        fun build(env: MockEnvironment) = RulesClient(RestClient.builder(), registry, env)
        shouldThrow<IllegalArgumentException> { build(environment(null)) }.message!! shouldContain
            RulesEdge.URL_PROPERTY
        for (value in listOf("", "   ", placeholder)) {
            shouldThrow<IllegalArgumentException> { build(environment(value)) }.message!! shouldContain
                RulesEdge.URL_PROPERTY
        }
        val lenient = environment(placeholder).apply { setIgnoreUnresolvableNestedPlaceholders(true) }
        shouldThrow<IllegalArgumentException> { build(lenient) }.message!! shouldContain RulesEdge.URL_PROPERTY
        build(environment(stub.url)).shouldBeInstanceOf<RulesClient>()
    }
}
