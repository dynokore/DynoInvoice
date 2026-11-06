package com.dynokore.gst.payload.adapters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.payload.adapters.clients.AccessReleaseDecisions
import com.dynokore.gst.payload.application.override.ReleaseDecisions
import com.dynokore.gst.payload.application.override.ReleaseRequest
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerProperties
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.RetryProperties
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One request the stub of access-svc saw: the path and the decoded query parameters, in order. */
private data class Seen(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>>,
)

/** A stub HTTP server of access-svc: it records every request and answers as [reply] says. */
private class AccessStub : AutoCloseable {
    class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    val seen = CopyOnWriteArrayList<Seen>()

    @Volatile
    var reply: Reply = Reply(500)

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@AccessStub.executor
            start()
        }

    val url: String = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.rawQuery.orEmpty()
        seen +=
            Seen(
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
 * [AccessReleaseDecisions] against a stub HTTP server of access-svc (INV-T-17184): the client is the generated one, the
 * builder is the bean `internalRestClientBuilder` of service-runtime and the policy is the registry's policy of the
 * edge `payload-svc->access-svc`.
 */
class AccessReleaseDecisionsTest {
    private val stub = AccessStub()
    private val clock = Clock.systemUTC()
    private val docUuid = UUID.randomUUID()

    @AfterEach
    fun stopStub() = stub.close()

    private val request =
        ReleaseRequest(
            entityRef = "ENT-1",
            docUuid = docUuid,
            version = 3,
            errorCode = "E-1001",
            totalSgd = BigDecimal("1234.50"),
            approverRef = "user-b",
            priorApproverRefs = listOf("user-a", "user-c"),
        )

    private fun registry(
        timeout: Duration = Duration.ofSeconds(5),
        breaker: CircuitBreakerProperties = CircuitBreakerProperties(),
    ): ResilienceRegistry =
        Resilience4jResilienceRegistry(
            ResilienceProperties(edges = mapOf(EDGE to EdgeProperties(timeout = timeout, circuitBreaker = breaker))),
            clock,
        )

    private fun runner(
        url: String?,
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
            .withUserConfiguration(AccessReleaseDecisions::class.java)
            .withPropertyValues(*listOfNotNull(url?.let { "payload.access-svc-url=$it" }).toTypedArray())

    private fun <T> withClient(
        registry: ResilienceRegistry = registry(),
        block: (ReleaseDecisions) -> T,
    ): T {
        var result: T? = null
        runner(stub.url, registry).run { context ->
            context.startupFailure shouldBe null
            result = block(context.getBean(ReleaseDecisions::class.java))
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun decide(
        reply: AccessStub.Reply,
        registry: ResilienceRegistry = registry(),
    ): ReleaseOutcome {
        stub.reply = reply
        return withClient(registry) { it.decide(request) }
    }

    private fun answer(
        decision: String,
        outcome: String? = null,
        reasonCode: String? = null,
    ) = AccessStub.Reply(
        200,
        buildString {
            append("""{"decision":"$decision","soleOperator":false""")
            if (outcome != null) append(""","outcome":"$outcome"""")
            if (reasonCode != null) append(""","reasonCode":"$reasonCode"""")
            append("}")
        },
    )

    private val releasing = """{"decision":"ALLOW","soleOperator":false,"outcome":"RELEASED"}"""

    // compared with the literals so that a renamed constant fails
    private val notOverridable = ReleaseOutcome.Denied("NOT_OVERRIDABLE")
    private val unavailable = ReleaseOutcome.Denied("ACCESS_DECISION_UNAVAILABLE")

    /** The breaker window of the counting tests: N = 3 calls (the production policy is not changed). */
    private val n = 3

    private val smallWindow =
        CircuitBreakerProperties(slidingWindowSize = n, waitDurationInOpenState = Duration.ofMinutes(5))

    private fun timingOut() = AccessStub.Reply(200, releasing, delayMillis = 1500)

    @Test
    fun `ALLOW with outcome RELEASED is Released`() {
        decide(answer("ALLOW", "RELEASED")) shouldBe ReleaseOutcome.Released
    }

    @Test
    fun `ALLOW with outcome AWAITS_SECOND_APPROVER is AwaitsSecond`() {
        decide(answer("ALLOW", "AWAITS_SECOND_APPROVER")) shouldBe ReleaseOutcome.AwaitsSecond
    }

    @Test
    fun `DENY with NOT_OVERRIDABLE is Denied with that reason code`() {
        decide(answer("DENY", reasonCode = "NOT_OVERRIDABLE")) shouldBe ReleaseOutcome.Denied("NOT_OVERRIDABLE")
    }

    @Test
    fun `DENY with SAME_PERSON_TWICE keeps its own reason code`() {
        decide(answer("DENY", reasonCode = "SAME_PERSON_TWICE")) shouldBe ReleaseOutcome.Denied("SAME_PERSON_TWICE")
    }

    @Test
    fun `the decision ALLOW with a trailing space is Denied NOT_OVERRIDABLE`() {
        decide(answer("ALLOW ", "RELEASED")) shouldBe notOverridable
    }

    @Test
    fun `the lower case outcome released is Denied NOT_OVERRIDABLE`() {
        decide(answer("ALLOW", "released")) shouldBe notOverridable
    }

    @Test
    fun `ALLOW without an outcome is Denied NOT_OVERRIDABLE`() {
        decide(answer("ALLOW")) shouldBe notOverridable
    }

    @Test
    fun `a 500 is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(AccessStub.Reply(500, releasing)) shouldBe unavailable
    }

    @Test
    fun `a 503 is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(AccessStub.Reply(503, releasing)) shouldBe unavailable
    }

    @Test
    fun `a 400 is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(AccessStub.Reply(400, releasing)) shouldBe unavailable
    }

    @Test
    fun `a body that is not JSON is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(AccessStub.Reply(200, "{")) shouldBe unavailable
    }

    @Test
    fun `an empty 200 body is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(AccessStub.Reply(200)) shouldBe unavailable
    }

    @Test
    fun `a timeout is Denied ACCESS_DECISION_UNAVAILABLE`() {
        decide(timingOut(), registry(timeout = Duration.ofMillis(300))) shouldBe unavailable
    }

    @Test
    fun `a refused connection is Denied ACCESS_DECISION_UNAVAILABLE`() {
        val closed = ServerSocket(0).use { it.localPort }
        runner("http://127.0.0.1:$closed", registry()).run { context ->
            context.getBean(ReleaseDecisions::class.java).decide(request) shouldBe unavailable
        }
    }

    @Test
    fun `an open breaker is Denied ACCESS_DECISION_UNAVAILABLE without a request to access-svc`() {
        stub.reply = AccessStub.Reply(500)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            val before = stub.seen.size
            // a body that would release: the open breaker must still deny and never reach the stub
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe unavailable
            stub.seen.size shouldBe before
        }
    }

    @Test
    fun `N calls answered 400 leave the breaker closed`() {
        stub.reply = AccessStub.Reply(400, releasing)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe ReleaseOutcome.Released
            stub.seen.size shouldBe n + 1
        }
    }

    @Test
    fun `N calls answered 500 open the breaker`() {
        stub.reply = AccessStub.Reply(500)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            val before = stub.seen.size
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe unavailable
            stub.seen.size shouldBe before
        }
    }

    @Test
    fun `N calls that time out open the breaker`() {
        stub.reply = timingOut()
        withClient(registry(timeout = Duration.ofMillis(200), breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            val before = stub.seen.size
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe unavailable
            stub.seen.size shouldBe before
        }
    }

    @Test
    fun `one call answered 400 reaches the stub once even when the edge retries`() {
        stub.reply = AccessStub.Reply(400, releasing)
        val retry =
            RetryProperties(maxAttempts = 2, initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(20))
        val withRetry =
            Resilience4jResilienceRegistry(
                ResilienceProperties(edges = mapOf(EDGE to EdgeProperties(retry = retry))),
                clock,
            )
        withClient(withRetry) { it.decide(request) } shouldBe unavailable
        stub.seen.size shouldBe 1
    }

    @Test
    fun `a 400 writes one WARN line with the status and the operation and not the body`() {
        val logger = LoggerFactory.getLogger(AccessReleaseDecisions::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            stub.reply = AccessStub.Reply(400, """{"secret":"STUB-BODY-MARKER"}""")
            withClient { it.decide(request) } shouldBe unavailable
        } finally {
            logger.detachAppender(appender)
        }
        val warns = appender.list.filter { it.level == Level.WARN }
        warns.size shouldBe 1
        val line = warns.single().formattedMessage
        line.contains("400") shouldBe true
        line.contains("decideOverrideRelease") shouldBe true
        line shouldNotContain "STUB-BODY-MARKER"
    }

    @Test
    fun `the request carries documentRef, documentVersion and documentTotalSgd with the entity and every approver`() {
        stub.reply = answer("ALLOW", "RELEASED")
        withClient { it.decide(request) } shouldBe ReleaseOutcome.Released
        val seen = stub.seen.single()
        seen.method shouldBe "GET"
        seen.path shouldBe "/internal/v1/access/override-release-decision"
        seen.query.toSet() shouldBe
            setOf(
                "entityRef" to "ENT-1",
                "errorCode" to "E-1001",
                "documentRef" to docUuid.toString(),
                "documentVersion" to "3",
                "documentTotalSgd" to "1234.50",
                "approverRef" to "user-b",
                "priorApproverRef" to "user-a",
                "priorApproverRef" to "user-c",
            ).also { seen.query.size shouldBe 8 }
    }

    @Test
    fun `INV-AC-17186 every failure denies and only an exact ALLOW with RELEASED releases`() {
        val cases =
            listOf(
                answer("ALLOW", "RELEASED") to ReleaseOutcome.Released,
                answer("ALLOW", "AWAITS_SECOND_APPROVER") to ReleaseOutcome.AwaitsSecond,
                answer("DENY", reasonCode = "NOT_OVERRIDABLE") to ReleaseOutcome.Denied("NOT_OVERRIDABLE"),
                answer("ALLOW ", "RELEASED") to notOverridable,
                answer("ALLOW", "released") to notOverridable,
                AccessStub.Reply(500) to unavailable,
                AccessStub.Reply(503) to unavailable,
                AccessStub.Reply(400) to unavailable,
                AccessStub.Reply(200, "{") to unavailable,
                AccessStub.Reply(200) to unavailable,
            )
        for ((reply, expected) in cases) {
            stub.seen.clear()
            decide(reply) shouldBe expected
            // the request carries documentRef, documentVersion and documentTotalSgd together
            val names =
                stub.seen
                    .first()
                    .query
                    .map { it.first }
            names shouldContain "documentRef"
            names shouldContain "documentVersion"
            names shouldContain "documentTotalSgd"
        }
        decide(timingOut(), registry(timeout = Duration.ofMillis(300))) shouldBe unavailable
        val closed = ServerSocket(0).use { it.localPort }
        runner("http://127.0.0.1:$closed", registry()).run { context ->
            context.getBean(ReleaseDecisions::class.java).decide(request) shouldBe unavailable
        }
        // N 400 answers: the breaker stays closed, the next call releases and the stub counted N + 1 requests
        stub.seen.clear()
        stub.reply = AccessStub.Reply(400, releasing)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe ReleaseOutcome.Released
            stub.seen.size shouldBe n + 1
        }
        // N 500 answers: the breaker is open, the next call is denied and does not reach the stub
        stub.seen.clear()
        stub.reply = AccessStub.Reply(500)
        withClient(registry(breaker = smallWindow)) { client ->
            repeat(n) { client.decide(request) shouldBe unavailable }
            val before = stub.seen.size
            stub.reply = answer("ALLOW", "RELEASED")
            client.decide(request) shouldBe unavailable
            stub.seen.size shouldBe before
        }
    }

    @Test
    fun `the context does not start without payload access-svc-url or with a blank one`() {
        runner(null, registry()).run { (it.startupFailure != null) shouldBe true }
        runner(" ", registry()).run { (it.startupFailure != null) shouldBe true }
    }

    private companion object {
        const val EDGE = "payload-svc->access-svc"
    }
}
