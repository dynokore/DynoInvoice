package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.MasterDataAnswer.Answered
import com.dynokore.gst.canonical.adapters.clients.MasterDataAnswer.Refused
import com.dynokore.gst.canonical.adapters.clients.MasterDataAnswer.Unavailable
import com.dynokore.gst.canonical.adapters.clients.masterdata.model.Counterparty
import com.dynokore.gst.canonical.adapters.clients.masterdata.model.IngestedCounterpartyRequest
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The client of masterdata-svc on the edge canonical-svc->masterdata-svc (INV-T-21372) against a JDK HttpServer stub:
 * the builder is a plain RestClient.Builder, the environment a MockEnvironment and the registry a fake that records the
 * edge names it is asked for and enforces the deadline it is given.
 */
class MasterDataClientTest {
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
    private val placeholder = "\${CANONICAL_MASTERDATA_SVC_URL}"
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-20T00:00:00Z"), ZoneOffset.UTC)

    @AfterEach
    fun stopStub() = stub.close()

    private fun environment(url: String?) =
        MockEnvironment().also { env -> url?.let { env.setProperty(MasterDataEdge.URL_PROPERTY, it) } }

    private fun client(
        registry: FakeRegistry,
        url: String = stub.url,
        clock: Clock = Clock.systemUTC(),
    ) = MasterDataClient(RestClient.builder(), environment(url), registry, clock)

    private fun build(
        environment: MockEnvironment,
        registry: FakeRegistry,
    ) = MasterDataClient(RestClient.builder(), environment, registry)

    private fun resolve(client: MasterDataClient) =
        client.resolveCounterparty("E1", "K1", "UEN", "V1", LocalDate.parse("2026-03-20"))

    private val key = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val ingested =
        IngestedCounterpartyRequest(
            entityRef = "E1",
            evidenceRef = "EVD-1",
            providedBy = IngestedCounterpartyRequest.ProvidedBy.COUNTERPARTY,
        )

    private val counterparty =
        """{"partyRef":"CTP-118","resolvedIdentifiers":[{"scheme":"UEN","value":"V1"}],"providedBy":"INTERNAL"}"""
    private val triple = """{"senderId":"0195:1","supplierId":"0195:1","supplierGstn":"201912345A"}"""
    private val envelope =
        """{"type":"about:blank","title":"x","status":404,"code":"SRT-005","correlationId":"c-1"}"""

    @Test
    fun `INV-AC-21371 the url property is required, not blank, not a placeholder, and the stub address is accepted`() {
        val registry = FakeRegistry(fixedClock)
        shouldThrow<IllegalArgumentException> { build(environment(null), registry) }.message!! shouldContain
            MasterDataEdge.URL_PROPERTY
        for (value in listOf("", "   ")) {
            shouldThrow<IllegalArgumentException> { client(registry, value) }.message!! shouldContain
                MasterDataEdge.URL_PROPERTY
        }
        shouldThrow<IllegalArgumentException> { client(registry, placeholder) }.message!! shouldContain
            MasterDataEdge.URL_PROPERTY
        // a resolver that leaves an unresolvable placeholder in the value (lenient) is refused by the client itself
        val lenient = environment(placeholder).apply { setIgnoreUnresolvableNestedPlaceholders(true) }
        shouldThrow<IllegalArgumentException> { build(lenient, registry) }.message!! shouldContain
            MasterDataEdge.URL_PROPERTY
        client(registry, stub.url).shouldBeInstanceOf<MasterDataClient>()
    }

    @Test
    fun `INV-AC-21371 the stub sees one GET with the query as a map and one POST with the key header and the body`() {
        val registry = FakeRegistry(fixedClock)
        val client = client(registry)
        stub.reply = Reply(200, """{"identityTriple":$triple,"counterparty":$counterparty}""")
        resolve(client)
        val get = stub.seen.single()
        get.method shouldBe "GET"
        get.path shouldBe "/internal/v1/masterdata/counterparties/resolution"
        get.query!!
            .split("&")
            .associate { it.substringBefore("=") to it.substringAfter("=") } shouldBe
            mapOf(
                "entityRef" to "E1",
                "constitution" to "K1",
                "partyScheme" to "UEN",
                "partyValue" to "V1",
                "transactionDate" to "2026-03-20",
            )
        stub.seen.clear()
        stub.reply = Reply(200, counterparty)
        client.recordIngestedCounterparty("CTP-118", key, ingested)
        val post = stub.seen.single()
        post.method shouldBe "POST"
        post.path shouldBe "/internal/v1/masterdata/counterparties/CTP-118/ingested-details"
        post.headers["idempotency-key"] shouldBe key.toString()
        val sent =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(post.body)
        sent["entityRef"].asString() shouldBe "E1"
        sent["evidenceRef"].asString() shouldBe "EVD-1"
        sent["providedBy"].asString() shouldBe "COUNTERPARTY"
    }

    @Test
    fun `INV-AC-21371 a 200 is Answered with the partyRef or null, and the record answer is the Counterparty`() {
        val client = client(FakeRegistry(fixedClock))
        stub.reply = Reply(200, """{"identityTriple":$triple,"counterparty":$counterparty}""")
        resolve(client) shouldBe Answered(ResolvedCounterparty("CTP-118"))
        stub.reply = Reply(200, """{"identityTriple":$triple}""")
        resolve(client) shouldBe Answered(ResolvedCounterparty(null))
        stub.reply = Reply(200, counterparty)
        val recorded = client.recordIngestedCounterparty("CTP-118", key, ingested)
        val value = recorded.shouldBeInstanceOf<Answered<*>>().value
        value.shouldBeInstanceOf<Counterparty>().partyRef shouldBe "CTP-118"
    }

    @Test
    fun `INV-AC-21371 a 404 is Refused with the status and the owner's code SRT-005 unchanged`() {
        val client = client(FakeRegistry(fixedClock))
        stub.reply = Reply(404, envelope)
        resolve(client) shouldBe Refused(404, "SRT-005")
        stub.reply = Reply(409, envelope.replace("SRT-005", "SRT-777"))
        resolve(client) shouldBe Refused(409, "SRT-777")
        stub.reply = Reply(400, "not json")
        resolve(client) shouldBe Refused(400, null)
    }

    @Test
    fun `INV-AC-21371 a 503, a timeout and a closed port are Unavailable and raise nothing`() {
        val registry = FakeRegistry(Clock.systemUTC())
        val client = client(registry)
        stub.reply = Reply(503, envelope.replace("404", "503").replace("SRT-005", "SRT-503"))
        resolve(client).shouldBeInstanceOf<Unavailable>()
        client.recordIngestedCounterparty("CTP-118", key, ingested).shouldBeInstanceOf<Unavailable>()
        stub.reply = Reply(200, counterparty, delayMillis = 2000)
        resolve(client(FakeRegistry(Clock.systemUTC(), enforceDeadline = true))).shouldBeInstanceOf<Unavailable>()
        val closed = ServerSocket(0).use { it.localPort }
        resolve(client(registry, "http://127.0.0.1:$closed")).shouldBeInstanceOf<Unavailable>()
    }

    @Test
    fun `INV-AC-21371 the deadline given to the policy is 300 ms`() {
        MasterDataEdge.DEADLINE shouldBe Duration.ofMillis(300)
        val registry = FakeRegistry(fixedClock)
        val client = client(registry, clock = fixedClock)
        stub.reply = Reply(200, counterparty)
        resolve(client)
        client.recordIngestedCounterparty("CTP-118", key, ingested)
        val remaining = registry.deadlines.map { it.remaining(fixedClock) }
        remaining shouldBe listOf(Duration.ofMillis(300), Duration.ofMillis(300))
    }

    @Test
    fun `INV-AC-21371 the registry is asked for exactly the edge canonical-svc to masterdata-svc once per call`() {
        MasterDataEdge.EDGE shouldBe "canonical-svc->masterdata-svc"
        val registry = FakeRegistry(fixedClock)
        val client = client(registry)
        registry.asked shouldBe emptyList()
        stub.reply = Reply(200, counterparty)
        resolve(client)
        client.recordIngestedCounterparty("CTP-118", key, ingested)
        registry.asked shouldBe listOf("canonical-svc->masterdata-svc", "canonical-svc->masterdata-svc")
    }
}
