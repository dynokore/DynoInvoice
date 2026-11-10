package com.dynokore.gst.rules.adapters.clients

import com.dynokore.gst.rules.application.InstallationApproverFact
import com.dynokore.gst.rules.application.MappingAcknowledgement
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalRequest
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.RetryProperties
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.springframework.web.client.RestClient
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-AC-19525: the client of the three decision operations of access-svc against an in-process stub (a JDK
 * `HttpServer`): the requests are compared with the access contract's operation, path and query by equality, and every
 * answer that is not a matching one is Unavailable (or NotRecorded for the 404 of a read), never an Allow.
 */
class AccessSvcClientTest {
    private class Seen(
        val method: String,
        val path: String,
        val query: List<Pair<String, String>>,
    )

    private class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile
    private var reply = Reply(500)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { handle(it) }
            executor = this@AccessSvcClientTest.executor
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val query =
            exchange.requestURI.rawQuery
                ?.split('&')
                ?.map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
                .orEmpty()
        seen += Seen(exchange.requestMethod, exchange.requestURI.path, query)
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

    @AfterEach
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun client(
        timeout: Duration = Duration.ofSeconds(5),
        retry: RetryProperties? = null,
        baseUrl: String = url,
        log: Logger = capture,
    ): AccessSvcClient {
        val clock = Clock.systemUTC()
        val registry: ResilienceRegistry =
            Resilience4jResilienceRegistry(
                ResilienceProperties(
                    edges = mapOf("rules-svc->access-svc" to EdgeProperties(timeout = timeout, retry = retry)),
                ),
                clock,
            )
        return AccessSvcClient(RestClient.builder(), registry, clock, baseUrl, log)
    }

    private val lines = CopyOnWriteArrayList<String>()
    private val capture: Logger =
        Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name in setOf("warn", "error", "info", "debug", "trace")) {
                lines +=
                    (args ?: emptyArray()).joinToString(
                        " ",
                    ) { a -> if (a is Array<*>) a.joinToString(" ") else a.toString() }
            }
            if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        } as Logger

    private val ackBody = """{"packVersion":"2026.3","clientRef":"CLI-2201","acknowledgedAt":"2026-09-27T02:00:00Z"}"""

    private val request =
        PackApprovalRequest(
            entityRef = "ENT-1",
            packId = "MAP-0001",
            packVersion = "2026.3",
            authorRef = "user-a",
            approverRef = "user-b",
            priorApproverRef = listOf("user-c", "user-d"),
        )

    private val allow =
        """{"decision":"ALLOW","soleOperator":false,"outstanding":["CLIENT_ACKNOWLEDGEMENT"],""" +
            """"option":"STAFF_APPROVER"}"""

    private fun decide(body: String): PackApprovalDecision {
        reply = Reply(200, body)
        return client().decide(request)
    }

    @Test
    fun `INV-AC-19525 decide sends the contract operation, path and query, one priorApproverRef per prior approver`() {
        reply = Reply(200, allow)
        client().decide(request)
        seen shouldHaveSize 1
        seen[0].method shouldBe "GET"
        seen[0].path shouldBe "/internal/v1/access/pack-approval-decision"
        seen[0].query.sortedBy { it.first + it.second } shouldBe
            listOf(
                "approverRef" to "user-b",
                "authorRef" to "user-a",
                "entityRef" to "ENT-1",
                "packRef" to "MAP-0001",
                "packVersion" to "2026.3",
                "priorApproverRef" to "user-c",
                "priorApproverRef" to "user-d",
            )
    }

    @Test
    fun `INV-AC-19525 decide with no prior approver sends no priorApproverRef`() {
        reply = Reply(200, allow)
        client().decide(request.copy(priorApproverRef = emptyList()))
        seen[0].query.map { it.first }.sorted() shouldBe
            listOf("approverRef", "authorRef", "entityRef", "packRef", "packVersion")
    }

    @Test
    fun `INV-AC-19525 a matching ALLOW answer maps to Allow`() {
        decide(allow) shouldBe
            PackApprovalDecision.Allow(false, null, "STAFF_APPROVER", setOf("CLIENT_ACKNOWLEDGEMENT"))
    }

    @Test
    fun `INV-AC-19525 a matching sole operator ALLOW answer carries its acknowledgement reference`() {
        decide(
            """{"decision":"ALLOW","soleOperator":true,"outstanding":[],"option":"SOLE_OPERATOR_ACK",""" +
                """"soleOperatorAckRef":"ACK-1"}""",
        ) shouldBe PackApprovalDecision.Allow(true, "ACK-1", "SOLE_OPERATOR_ACK", emptySet())
    }

    @Test
    fun `INV-AC-19525 an ALLOW without option is Unavailable and logged once naming option`() {
        decide("""{"decision":"ALLOW","soleOperator":false,"outstanding":["SECRET-OUTSTANDING"]}""") shouldBe
            PackApprovalDecision.Unavailable
        lines shouldHaveSize 1
        lines[0].contains("option") shouldBe true
        lines[0].contains("SECRET-OUTSTANDING") shouldBe false
    }

    @Test
    fun `INV-AC-19525 a DENY without reasonCode is Unavailable and logged once naming reasonCode`() {
        decide("""{"decision":"DENY","soleOperator":false,"outstanding":["SECRET-OUTSTANDING"]}""") shouldBe
            PackApprovalDecision.Unavailable
        lines shouldHaveSize 1
        lines[0].contains("reasonCode") shouldBe true
        lines[0].contains("SECRET-OUTSTANDING") shouldBe false
    }

    @Test
    fun `INV-AC-19525 a matching DENY answer maps to Deny`() {
        decide(
            """{"decision":"DENY","soleOperator":false,"outstanding":[],"reasonCode":"AUTHOR_IS_APPROVER"}""",
        ) shouldBe PackApprovalDecision.Deny("AUTHOR_IS_APPROVER")
    }

    @Test
    fun `INV-AC-19525 decide with a 500 is Unavailable`() {
        reply = Reply(500, allow)
        client().decide(request) shouldBe PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with a 404 is Unavailable, never an Allow`() {
        reply = Reply(404, allow)
        client().decide(request) shouldBe PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with a timeout is Unavailable`() {
        reply = Reply(200, allow, delayMillis = 1500)
        client(timeout = Duration.ofMillis(200)).decide(request) shouldBe PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with no answer is Unavailable`() {
        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        client(baseUrl = "http://127.0.0.1:$closed").decide(request) shouldBe PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with a decision in another case is Unavailable`() {
        decide("""{"decision":"allow","soleOperator":false,"outstanding":[],"option":"STAFF_APPROVER"}""") shouldBe
            PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with a body without soleOperator is Unavailable`() {
        decide("""{"decision":"ALLOW","outstanding":[],"option":"STAFF_APPROVER"}""") shouldBe
            PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with soleOperator as the string false is Unavailable`() {
        decide("""{"decision":"ALLOW","soleOperator":"false","outstanding":[],"option":"STAFF_APPROVER"}""") shouldBe
            PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 decide with an unknown decision or a body that is not JSON is Unavailable`() {
        decide("""{"decision":"MAYBE","soleOperator":false,"outstanding":[]}""") shouldBe
            PackApprovalDecision.Unavailable
        decide("not json") shouldBe PackApprovalDecision.Unavailable
        decide("") shouldBe PackApprovalDecision.Unavailable
    }

    @Test
    fun `INV-AC-19525 a 500 is retried once under the edge policy and then Unavailable`() {
        reply = Reply(500)
        val retry =
            RetryProperties(maxAttempts = 2, initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(20))
        client(retry = retry).decide(request) shouldBe PackApprovalDecision.Unavailable
        seen shouldHaveSize 2
    }

    @Test
    fun `INV-AC-19525 getInstallationApproverFact sends the contract operation and path, Recorded`() {
        reply = Reply(200, """{"distinctTaxApprover":true,"recordedAt":"2026-10-25T08:00:00Z"}""")
        client().read() shouldBe InstallationApproverFact.Recorded(true)
        seen shouldHaveSize 1
        seen[0].method shouldBe "GET"
        seen[0].path shouldBe "/internal/v1/access/installation/approver-fact"
        seen[0].query shouldBe emptyList()
        reply = Reply(200, """{"distinctTaxApprover":false,"recordedAt":"2026-10-25T08:00:00Z"}""")
        client().read() shouldBe InstallationApproverFact.Recorded(false)
    }

    @Test
    fun `INV-AC-19525 getInstallationApproverFact 404 is NotRecorded`() {
        reply = Reply(404, """{"code":"SRT-005"}""")
        client().read() shouldBe InstallationApproverFact.NotRecorded
    }

    @Test
    fun `INV-AC-19525 getInstallationApproverFact 500, timeout, no answer are Unavailable`() {
        reply = Reply(500)
        client().read() shouldBe InstallationApproverFact.Unavailable
        reply = Reply(200, """{"distinctTaxApprover":false,"recordedAt":"2026-10-25T08:00:00Z"}""", delayMillis = 1500)
        client(timeout = Duration.ofMillis(200)).read() shouldBe InstallationApproverFact.Unavailable
        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        client(baseUrl = "http://127.0.0.1:$closed").read() shouldBe InstallationApproverFact.Unavailable
    }

    @Test
    fun `INV-AC-19525 getInstallationApproverFact with distinctTaxApprover as the string false is Unavailable`() {
        reply = Reply(200, """{"distinctTaxApprover":"false","recordedAt":"2026-10-25T08:00:00Z"}""")
        client().read() shouldBe InstallationApproverFact.Unavailable
    }

    @Test
    fun `INV-AC-19525 getInstallationApproverFact with a missing distinctTaxApprover is Unavailable`() {
        reply = Reply(200, """{"recordedAt":"2026-10-25T08:00:00Z"}""")
        client().read() shouldBe InstallationApproverFact.Unavailable
    }

    @Test
    fun `INV-AC-19525 getMappingAcknowledgement sends the contract operation, path and query, matching is Recorded`() {
        reply = Reply(200, ackBody)
        client().read("2026.3", "CLI-2201") shouldBe MappingAcknowledgement.Recorded
        seen shouldHaveSize 1
        seen[0].method shouldBe "GET"
        seen[0].path shouldBe "/internal/v1/access/mapping-acknowledgement"
        seen[0].query.sortedBy { it.first } shouldBe listOf("clientRef" to "CLI-2201", "packVersion" to "2026.3")
    }

    @Test
    fun `INV-AC-19525 getMappingAcknowledgement 404 is NotRecorded`() {
        reply = Reply(404, """{"code":"SRT-005"}""")
        client().read("2026.3", "CLI-2201") shouldBe MappingAcknowledgement.NotRecorded
    }

    @Test
    fun `INV-AC-19525 getMappingAcknowledgement 500, timeout, no answer, malformed body are Unavailable`() {
        reply = Reply(500)
        client().read("2026.3", "CLI-2201") shouldBe MappingAcknowledgement.Unavailable
        reply = Reply(200, ackBody, 1500)
        client(timeout = Duration.ofMillis(200)).read("2026.3", "CLI-2201") shouldBe MappingAcknowledgement.Unavailable
        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        client(baseUrl = "http://127.0.0.1:$closed").read("2026.3", "CLI-2201") shouldBe
            MappingAcknowledgement.Unavailable
        reply = Reply(200, """{"packVersion":"2026.3"}""")
        client().read("2026.3", "CLI-2201") shouldBe MappingAcknowledgement.Unavailable
    }
}
