package com.dynokore.gst.payload.adapters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.payload.adapters.clients.MasterdataRegisterStatusSource
import com.dynokore.gst.payload.application.preissuance.RegisterLookup
import com.dynokore.gst.payload.application.preissuance.RegisterQuery
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerProperties
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One request the stub of masterdata-svc saw: the path and the decoded query parameters, in order. */
private data class MdSeen(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>>,
)

/** A stub HTTP server of masterdata-svc: it records every request and answers as [reply] says. */
private class MasterdataStub : AutoCloseable {
    class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    val seen = CopyOnWriteArrayList<MdSeen>()

    @Volatile
    var reply: Reply = Reply(500)

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@MasterdataStub.executor
            start()
        }

    val url: String = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.rawQuery.orEmpty()
        seen +=
            MdSeen(
                exchange.requestMethod,
                exchange.requestURI.path,
                raw.split('&').filter { it.isNotEmpty() }.map {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                },
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

/**
 * [MasterdataRegisterStatusSource] against a stub HTTP server of masterdata-svc (INV-T-24314): the client is the
 * generated one, the builder is the bean `internalRestClientBuilder` of service-runtime and the policy is the
 * registry's policy of the edge `payload-svc->masterdata-svc`.
 */
class MasterdataRegisterStatusSourceTest {
    private val stub = MasterdataStub()
    private val clock = Clock.systemUTC()

    @AfterEach
    fun stopStub() = stub.close()

    private val query = RegisterQuery("200012345K", "ENT-1", "SGD", LocalDate.of(2026, 3, 19))
    private val unavailable = RegisterLookup.Unavailable
    private val n = 3
    private val smallWindow =
        CircuitBreakerProperties(slidingWindowSize = n, waitDurationInOpenState = Duration.ofMinutes(5))

    private fun registry(
        timeout: Duration = Duration.ofSeconds(5),
        breaker: CircuitBreakerProperties = CircuitBreakerProperties(),
    ): ResilienceRegistry =
        Resilience4jResilienceRegistry(
            ResilienceProperties(edges = mapOf(EDGE to EdgeProperties(timeout = timeout, circuitBreaker = breaker))),
            clock,
        )

    private fun runner(
        url: String,
        registry: ResilienceRegistry,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    InternalRestClientAutoConfiguration::class.java,
                    PropertyPlaceholderAutoConfiguration::class.java,
                ),
            ).withBean(ResilienceRegistry::class.java, { registry })
            .withBean(Clock::class.java, { clock })
            .withUserConfiguration(MasterdataRegisterStatusSource::class.java)
            .withPropertyValues("payload.masterdata-svc-url=$url")

    private fun <T> withClient(
        registry: ResilienceRegistry = registry(),
        block: (RegisterStatusSource) -> T,
    ): T {
        var result: T? = null
        runner(stub.url, registry).run { context ->
            context.startupFailure shouldBe null
            result = block(context.getBean(RegisterStatusSource::class.java))
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun lookup(
        reply: MasterdataStub.Reply,
        registry: ResilienceRegistry = registry(),
    ): RegisterLookup {
        stub.reply = reply
        return withClient(registry) { it.lookup(query) }
    }

    private fun status(
        value: String,
        asOf: String = "2026-03-19",
    ) = MasterdataStub.Reply(200, """{"register":{"status":"$value","asOf":"$asOf"}}""")

    private val answeredBody = status("REGISTERED")

    @Test
    fun `INV-AC-24312 the request carries the GSTN, entity, currency and date unchanged`() {
        stub.reply = answeredBody
        val odd = RegisterQuery("200012345k ", "ENT-1", "SGD", LocalDate.of(2026, 3, 19))
        withClient { it.lookup(odd) }
        val seen = stub.seen.single()
        seen.method shouldBe "GET"
        seen.path shouldBe "/internal/v1/masterdata/register-and-rates"
        seen.query.toSet() shouldBe
            setOf(
                "counterpartyUen" to "200012345k ",
                "entityRef" to "ENT-1",
                "currency" to "SGD",
                "transactionDate" to "2026-03-19",
            )
        seen.query.size shouldBe 4
        stub.seen.clear()
        withClient { it.lookup(query) }
        stub.seen
            .single()
            .query
            .toMap()["counterpartyUen"] shouldBe "200012345K"
    }

    @Test
    fun `INV-AC-24312 the four 200 answers give Answered with the status exactly as sent`() {
        for (value in listOf("REGISTERED", "NOT_REGISTERED", "REGISTER_UNAVAILABLE", "SUSPENDED")) {
            lookup(status(value)) shouldBe RegisterLookup.Answered(value, LocalDate.of(2026, 3, 19))
        }
        lookup(MasterdataStub.Reply(200, """{"register":{"status":"SUSPENDED"}}""")) shouldBe
            RegisterLookup.Answered("SUSPENDED", null)
    }

    @Test
    fun `INV-AC-24312 every failure of the call gives Unavailable`() {
        val cases =
            listOf(
                MasterdataStub.Reply(400, """{"register":{"status":"REGISTERED"}}"""),
                MasterdataStub.Reply(500),
                MasterdataStub.Reply(503),
                MasterdataStub.Reply(200, "{"),
                MasterdataStub.Reply(200, "[1]"),
                MasterdataStub.Reply(200),
                MasterdataStub.Reply(200, """{"rate":{}}"""),
                MasterdataStub.Reply(200, """{"register":{"status":5}}"""),
                MasterdataStub.Reply(200, """{"register":{}}"""),
            )
        for (reply in cases) lookup(reply) shouldBe unavailable
        lookup(
            MasterdataStub.Reply(200, """{"register":{"status":"REGISTERED"}}""", delayMillis = 1500),
            registry(timeout = Duration.ofMillis(300)),
        ) shouldBe unavailable
    }

    @Test
    fun `INV-AC-24312 a refused connection gives Unavailable`() {
        val closed = ServerSocket(0).use { it.localPort }
        runner("http://127.0.0.1:$closed", registry()).run { context ->
            context.getBean(RegisterStatusSource::class.java).lookup(query) shouldBe unavailable
        }
    }

    @Test
    fun `INV-AC-24312 an open breaker gives Unavailable and sends no request`() {
        stub.reply = MasterdataStub.Reply(500)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.lookup(query) shouldBe unavailable }
            val before = stub.seen.size
            stub.reply = answeredBody
            client.lookup(query) shouldBe unavailable
            stub.seen.size shouldBe before
        }
    }

    @Test
    fun `INV-AC-24312 a deadline shorter than the contract timeout is taken from CurrentDeadline`() {
        stub.reply = answeredBody
        val spent = Deadline.after(Duration.ofMillis(1), clock)
        Thread.sleep(20)
        // the deadline of the request being served is spent: no fresh 1000 ms deadline is made, no request is sent
        withClient { client -> CurrentDeadline.callWith(spent) { client.lookup(query) } } shouldBe unavailable
        stub.seen.size shouldBe 0
        // with no deadline behind the call, the contract timeout applies and the stub is reached
        withClient { it.lookup(query) } shouldBe RegisterLookup.Answered("REGISTERED", LocalDate.of(2026, 3, 19))
        stub.seen.size shouldBe 1
    }

    @Test
    fun `INV-AC-24312 a 400 writes one WARN line with the status and the operation and not the GSTN or body`() {
        val logger = LoggerFactory.getLogger(MasterdataRegisterStatusSource::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            stub.reply = MasterdataStub.Reply(400, """{"secret":"STUB-BODY-MARKER"}""")
            withClient { it.lookup(query) } shouldBe unavailable
        } finally {
            logger.detachAppender(appender)
        }
        val warns = appender.list.filter { it.level == Level.WARN }
        warns.size shouldBe 1
        val line = warns.single().formattedMessage
        line.contains("400") shouldBe true
        line.contains("getRegisterAndRates") shouldBe true
        line shouldNotContain "STUB-BODY-MARKER"
        line shouldNotContain "200012345K"
    }

    @Test
    fun `INV-AC-24312 a query with a blank member is refused before any call`() {
        val date = LocalDate.of(2026, 3, 19)
        shouldThrow<IllegalArgumentException> { RegisterQuery(" ", "ENT-1", "SGD", date) }
        shouldThrow<IllegalArgumentException> { RegisterQuery("", "ENT-1", "SGD", date) }
        shouldThrow<IllegalArgumentException> { RegisterQuery("200012345K", "", "SGD", date) }
        shouldThrow<IllegalArgumentException> { RegisterQuery("200012345K", "ENT-1", " ", date) }
        stub.seen.size shouldBe 0
    }

    private companion object {
        const val EDGE = "payload-svc->masterdata-svc"
    }
}
