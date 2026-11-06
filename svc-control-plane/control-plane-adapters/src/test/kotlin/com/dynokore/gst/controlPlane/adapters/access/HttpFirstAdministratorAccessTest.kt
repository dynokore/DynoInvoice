package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAnswer
import com.dynokore.gst.controlPlane.application.commercial.ProvisionFirstAdministratorCall
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorCall
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The request that the access contract stub receives from control-plane-svc (INV-AC-8224, INV-AC-8281). */
class HttpFirstAdministratorAccessTest {
    private class Seen(
        val method: String,
        val path: String,
        val body: String,
        val key: String?,
        val workload: String?,
        val authorization: String?,
    )

    private val seen = ArrayList<Seen>()
    private var status = 201
    private var delayMillis = 0L
    private var answerBody: String? = null
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.path,
                        String(exchange.requestBody.readAllBytes()),
                        exchange.requestHeaders.getFirst("Idempotency-Key"),
                        exchange.requestHeaders.getFirst(WorkloadToken.HEADER_NAME),
                        exchange.requestHeaders.getFirst("Authorization"),
                    )
                Thread.sleep(delayMillis)
                val answer =
                    (
                        answerBody
                            ?: ("""{"grantRef":"GRT-1","status":"PENDING","invitationRef":"INVN-1",""" + """"invitationCode":"code-1"}""")
                    ).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun access(timeout: Duration = Duration.ofSeconds(2)) =
        HttpFirstAdministratorAccess(
            URI.create("http://127.0.0.1:${server.address.port}"),
            WorkloadTokenSource { WorkloadToken("workload-token-1") },
            timeout,
        )

    private val key = UUID.fromString("7d444840-9dc0-41a0-b0e8-6b4a0e9c9a11")
    private val expiry = Instant.parse("2026-11-05T08:00:00Z")

    private fun provision() =
        access().provision(ProvisionFirstAdministratorCall(CommercialRef("TEN-1"), "a@b.example", expiry, key))

    @Test
    fun `INV-AC-8224 the stub receives the provision request with the service identity and no end-user token`() {
        val answer = provision()
        val request = seen.single()
        request.method shouldBe "POST"
        request.path shouldBe "/internal/v1/access/first-administrators"
        request.body shouldBe
            """{"tenantRef":"TEN-1","administratorEmail":"a@b.example","invitationExpiresAt":"2026-11-05T08:00:00Z"}"""
        request.key shouldBe key.toString()
        request.workload shouldBe "workload-token-1"
        request.authorization shouldBe null
        answer.shouldBeInstanceOf<FirstAdministratorAnswer.Issued>().let {
            it.invitationRef shouldBe "INVN-1"
            it.invitationCode.value shouldBe "code-1"
        }
    }

    @Test
    fun `INV-AC-8281 the stub receives the reissue request on the tenant path with the same key`() {
        status = 200
        val answer = access().reissue(ReissueFirstAdministratorCall(CommercialRef("TEN-1"), expiry, key))
        val request = seen.single()
        request.path shouldBe "/internal/v1/access/first-administrators/TEN-1/invitation-reissues"
        request.body shouldBe """{"invitationExpiresAt":"2026-11-05T08:00:00Z"}"""
        request.key shouldBe key.toString()
        request.authorization shouldBe null
        answer.shouldBeInstanceOf<FirstAdministratorAnswer.Issued>()
    }

    @Test
    fun `INV-AC-8224 409 is a refusal, 503 and a slow answer are unavailable, other 5xx and statuses failed`() {
        status = 409
        provision() shouldBe FirstAdministratorAnswer.Refused
        status = 503
        provision() shouldBe FirstAdministratorAnswer.Unavailable
        listOf(500, 502, 504, 403, 400, 404).forEach {
            status = it
            provision() shouldBe FirstAdministratorAnswer.Failed
        }
        status = 201
        delayMillis = 1500
        access(Duration.ofMillis(200))
            .provision(ProvisionFirstAdministratorCall(CommercialRef("TEN-1"), "a@b.example", expiry, key)) shouldBe
            FirstAdministratorAnswer.Unavailable
    }

    @Test
    fun `INV-AC-8224 an unreachable access-svc is unavailable`() {
        val dead =
            HttpFirstAdministratorAccess(URI.create("http://127.0.0.1:1"), WorkloadTokenSource { WorkloadToken("t") })
        dead.provision(ProvisionFirstAdministratorCall(CommercialRef("TEN-1"), "a@b.example", expiry, key)) shouldBe
            FirstAdministratorAnswer.Unavailable
    }

    @Test
    fun `INV-AC-8281 a 2xx answer that is not json or has no ref and code is failed, not unavailable`() {
        listOf(200, 201).forEach { code ->
            status = code
            listOf("not json", "{}", "", """{"invitationRef":"INVN-1"}""", """{"invitationCode":"c"}""").forEach {
                answerBody = it
                provision() shouldBe FirstAdministratorAnswer.Failed
                access().reissue(ReissueFirstAdministratorCall(CommercialRef("TEN-1"), expiry, key)) shouldBe
                    FirstAdministratorAnswer.Failed
            }
        }
    }
}
