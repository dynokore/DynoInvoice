package com.dynokore.gst.submission.adapters.clients

import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenPropagationInterceptor
import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import com.dynokore.platform.serviceruntime.replica.BreakGlassActionReporter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.client.ClientHttpRequestInterceptor
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** INV-AC-9346: the reporter against a stub HTTP server of access-svc for `recordBreakGlassAction`. */
class AccessBreakGlassReporterTest {
    private class Seen(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile private var status = 201

    @Volatile private var delayMillis = 0L
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { handle(it) }
            executor = this@AccessBreakGlassReporterTest.executor
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val mapper = JsonMapper.builder().build()

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        seen +=
            Seen(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                body,
            )
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val answer = """{"actionRef":"ACT-1","unlockRef":"BG-1","action":"READINESS"}""".toByteArray()
        runCatching {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, answer.size.toLong())
            exchange.responseBody.write(answer)
        }
        exchange.close()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun runner(property: String?): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))
            .withBean(
                "workload",
                ClientHttpRequestInterceptor::class.java,
                { WorkloadTokenPropagationInterceptor { WorkloadToken("workload-token-of-submission") } },
            ).withUserConfiguration(AccessBreakGlassReporter::class.java)
            .withPropertyValues(*listOfNotNull(property?.let { "$URL_PROPERTY=$it" }).toTypedArray())

    private companion object {
        const val URL_PROPERTY = "product-runtime.fallback.access-svc-url"
    }

    private val target = "GET /v1/entities/{entityRef}/periods/{periodRef}/readiness"
    private val longest = "POST /internal/v1/transactions/{docUuid}/versions/{version}/replay"

    private fun report(
        reporterTarget: String = target,
        action: BreakGlassAction = BreakGlassAction.READINESS,
    ) {
        runner(url).run { context ->
            context.startupFailure shouldBe null
            context
                .getBean(BreakGlassActionReporter::class.java)
                .report("BG-1", action, reporterTarget, Instant.EPOCH)
        }
    }

    @Test
    fun `INV-AC-9346 a 201 returns normally after one POST with exactly action and target and a UUID key`() {
        report()
        val request = seen.single()
        request.method shouldBe "POST"
        request.path shouldBe "/internal/v1/access/break-glass/BG-1/actions"
        val members = mapper.readTree(request.body)
        members.propertyNames().toSet() shouldBe setOf("action", "target")
        members.get("action").asString() shouldBe "READINESS"
        members.get("target").asString() shouldBe target
        UUID.fromString(request.headers.getValue("idempotency-key")).toString() shouldBe
            request.headers.getValue("idempotency-key")
        request.headers["x-workload-token"] shouldBe "workload-token-of-submission"
    }

    @Test
    fun `INV-AC-9346 each call carries a new Idempotency-Key`() {
        report()
        report()
        seen.map { it.headers.getValue("idempotency-key") }.toSet().size shouldBe 2
    }

    @Test
    fun `INV-AC-9346 the longest target is sent unchanged`() {
        report(longest, BreakGlassAction.REPLAY)
        mapper.readTree(seen.single().body).get("target").asString() shouldBe longest
        (longest.length <= 128) shouldBe true
    }

    @Test
    fun `INV-AC-9346 every status but 201 throws after exactly one request`() {
        for (code in listOf(200, 400, 401, 403, 404, 409, 500)) {
            seen.clear()
            status = code
            shouldThrow<RuntimeException> { report() }
            seen.size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-9346 no answer within 500 milliseconds throws after exactly one request`() {
        delayMillis = 1500
        shouldThrow<RuntimeException> { report() }
        seen.size shouldBe 1
    }

    @Test
    fun `INV-AC-9346 a refused connection throws`() {
        val closed = ServerSocket(0).use { it.localPort }
        runner("http://127.0.0.1:$closed").run { context ->
            shouldThrow<RuntimeException> {
                context
                    .getBean(BreakGlassActionReporter::class.java)
                    .report("BG-1", BreakGlassAction.VIEW, target, Instant.EPOCH)
            }
        }
    }

    @Test
    fun `INV-AC-9346 without the property no reporter bean exists`() {
        runner(null).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(BreakGlassActionReporter::class.java).size shouldBe 0
        }
    }
}
