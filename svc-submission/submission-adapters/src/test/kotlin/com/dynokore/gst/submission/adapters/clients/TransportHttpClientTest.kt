package com.dynokore.gst.submission.adapters.clients

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.application.queue.TransportClient
import com.dynokore.gst.submission.application.queue.TransportRequest
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.tenancykernel.TenantId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** INV-AC-22120: the TransportClient adapter against a local double of transport-svc for `submitPayload`. */
class TransportHttpClientTest {
    private class Seen(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile private var status = 202

    @Volatile private var answer: ByteArray = ByteArray(0)

    @Volatile private var delayMillis = 0L

    @Volatile private var closeWithoutAnswer = false
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { handle(it) }
            executor = this@TransportHttpClientTest.executor
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val mapper = JsonMapper.builder().build()
    private val docUuid = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val payload = "<Invoice>payload</Invoice>".toByteArray()

    private fun handle(exchange: HttpExchange) {
        seen +=
            Seen(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                exchange.requestBody.readAllBytes(),
            )
        if (closeWithoutAnswer) {
            exchange.close()
            return
        }
        if (delayMillis > 0) Thread.sleep(delayMillis)
        runCatching {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (answer.isEmpty()) -1 else answer.size.toLong())
            if (answer.isNotEmpty()) exchange.responseBody.write(answer)
        }
        exchange.close()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun outcome(
        outcome: String,
        extra: String = "",
    ) = """{"docUuid":"$docUuid","version":2,"outcome":"$outcome"$extra}"""

    private fun answer(
        code: Int,
        body: String,
    ) {
        status = code
        answer = body.toByteArray()
    }

    private fun submit(
        baseUrl: String = url,
        request: TransportRequest = request(payload),
    ): TransportResult {
        var result: TransportResult? = null
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))
            .withUserConfiguration(TransportHttpClient::class.java)
            .withPropertyValues(
                "$PREFIX.base-url=$baseUrl",
                "$PREFIX.read-timeout-ms=$TIMEOUT_MS",
            ).run { context ->
                context.startupFailure shouldBe null
                result = context.getBean(TransportClient::class.java).submit(request)
            }
        return result!!
    }

    private fun request(bytes: ByteArray?) =
        TransportRequest(TenantId(UUID.randomUUID()), docUuid.toString(), 2, "ENT-1", bytes, key)

    private fun transient(result: TransportResult) {
        val failure = result.shouldBeInstanceOf<TransportResult.TransientFailure>()
        failure.code shouldBe "SUB-001"
    }

    @Test
    fun `INV-AC-22120 the transient code is a catalogue entry of a transport timeout or connection failure`() {
        ErrorCode.fromCode("SUB-001")!!.code shouldBe "SUB-001"
    }

    @Test
    fun `INV-AC-22120 202 ACCEPTED with an acknowledgement gives Accepted with reference, time and bytes`() {
        val body =
            outcome("ACCEPTED", ""","acknowledgement":{"reference":"ACK-9","receivedAt":"2026-11-14T01:02:03Z"}""")
        answer(202, body)
        val result = submit().shouldBeInstanceOf<TransportResult.Accepted>()
        result.acknowledgementId shouldBe "ACK-9"
        result.receivedAt shouldBe Instant.parse("2026-11-14T01:02:03Z")
        result.responseBytes.toList() shouldBe body.toByteArray().toList()
    }

    @Test
    fun `INV-AC-22120 202 REJECTED with two reasons gives Rejected with the reasons in order`() {
        val body =
            outcome(
                "REJECTED",
                ""","reasons":[{"code":"R1","field":"f1","message":"m1"},{"code":"R2","message":"m2"}]""",
            )
        answer(202, body)
        val result = submit().shouldBeInstanceOf<TransportResult.Rejected>()
        result.reasons.map { Triple(it.code, it.field, it.message) } shouldBe
            listOf(Triple("R1", "f1", "m1"), Triple("R2", null, "m2"))
        result.responseBytes.toList() shouldBe body.toByteArray().toList()
    }

    @Test
    fun `INV-AC-22120 202 REJECTED with no reasons gives TransientFailure`() {
        answer(202, outcome("REJECTED"))
        transient(submit())
        answer(202, outcome("REJECTED", ""","reasons":[]"""))
        transient(submit())
    }

    @Test
    fun `INV-AC-22120 202 TRANSIENT_FAILURE gives TransientFailure with the answered bytes`() {
        val body = outcome("TRANSIENT_FAILURE")
        answer(202, body)
        val result = submit()
        transient(result)
        result.responseBytes.toList() shouldBe body.toByteArray().toList()
    }

    @Test
    fun `INV-AC-22120 202 with an outcome value outside the enum gives TransientFailure`() {
        answer(
            202,
            outcome("SOMETHING_NEW", ""","acknowledgement":{"reference":"A","receivedAt":"2026-11-14T01:02:03Z"}"""),
        )
        transient(submit())
    }

    @Test
    fun `INV-AC-22120 500 gives TransientFailure with the answered bytes`() {
        answer(500, """{"error":"boom"}""")
        val result = submit()
        transient(result)
        result.responseBytes.toList() shouldBe """{"error":"boom"}""".toByteArray().toList()
        seen.size shouldBe 1
    }

    @Test
    fun `INV-AC-22120 a body that is not JSON gives TransientFailure with the answered bytes`() {
        answer(202, "not json at all")
        val result = submit()
        transient(result)
        result.responseBytes.toList() shouldBe "not json at all".toByteArray().toList()
    }

    @Test
    fun `INV-AC-22120 a closed connection gives TransientFailure and no exception`() {
        closeWithoutAnswer = true
        val result = submit()
        transient(result)
        result.responseBytes.size shouldBe 0
        val refused = ServerSocket(0).use { it.localPort }
        transient(submit("http://127.0.0.1:$refused"))
    }

    @Test
    fun `INV-AC-22120 a read timeout gives TransientFailure after one request`() {
        delayMillis = TIMEOUT_MS * 5
        answer(202, outcome("TRANSIENT_FAILURE"))
        transient(submit())
        seen.size shouldBe 1
    }

    @Test
    fun `INV-AC-22120 202 ACCEPTED with no acknowledgement gives TransientFailure`() {
        answer(202, outcome("ACCEPTED"))
        transient(submit())
    }

    @Test
    fun `INV-AC-22120 202 with a body that is not a TransportOutcome gives TransientFailure with the bytes`() {
        val body = """{"hello":"world"}"""
        answer(202, body)
        val result = submit()
        transient(result)
        result.responseBytes.toList() shouldBe body.toByteArray().toList()
        answer(202, "[1,2]")
        transient(submit())
    }

    @Test
    fun `INV-AC-22120 200 with a valid ACCEPTED body gives TransientFailure`() {
        answer(
            200,
            outcome("ACCEPTED", ""","acknowledgement":{"reference":"ACK-9","receivedAt":"2026-11-14T01:02:03Z"}"""),
        )
        transient(submit())
    }

    @Test
    fun `INV-AC-22120 the request carries the key and the members payload, entityRef, docUuid and version`() {
        answer(202, outcome("TRANSIENT_FAILURE"))
        submit()
        val request = seen.single()
        request.method shouldBe "POST"
        request.path shouldBe "/internal/v1/transport/payloads"
        request.headers.getValue("idempotency-key") shouldBe key.toString()
        val members = mapper.readTree(request.body)
        members.propertyNames().toSet() shouldBe setOf("docUuid", "version", "payload", "entityRef")
        members.get("docUuid").asString() shouldBe docUuid.toString()
        members.get("version").asInt() shouldBe 2
        members.get("entityRef").asString() shouldBe "ENT-1"
        members.get("payload").asString() shouldBe Base64.getEncoder().encodeToString(payload)
    }

    @Test
    fun `INV-AC-22120 the double sees exactly one request per call, also after a 500`() {
        answer(500, "{}")
        submit()
        seen.size shouldBe 1
        answer(202, outcome("TRANSIENT_FAILURE"))
        submit()
        seen.size shouldBe 2
    }

    @Test
    fun `INV-AC-22120 a null payload sends no request and fails with IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { submit(request = request(null)) }
        seen.size shouldBe 0
    }

    @Test
    fun `INV-AC-22120 an empty payload sends no request and fails with IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { submit(request = request(ByteArray(0))) }
        seen.size shouldBe 0
    }

    @Test
    fun `INV-AC-22120 without the base-url no TransportClient bean exists`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))
            .withUserConfiguration(TransportHttpClient::class.java)
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeansOfType(TransportClient::class.java).size shouldBe 0
            }
    }

    private companion object {
        const val PREFIX = "inv.transport-client"
        const val TIMEOUT_MS = 400L
    }
}
