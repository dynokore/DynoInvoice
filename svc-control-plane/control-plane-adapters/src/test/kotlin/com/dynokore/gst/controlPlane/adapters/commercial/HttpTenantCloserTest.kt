package com.dynokore.gst.controlPlane.adapters.commercial

import com.dynokore.gst.controlPlane.adapters.tenancy.HttpTenantCloser
import com.dynokore.gst.controlPlane.application.schedule.TenantClosing
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant

/** The closeTenant request that the tenancy contract stub receives from the scheduled job (INV-REQ-8100, INV-Q-8137). */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class HttpTenantCloserTest {
    private data class Received(
        val path: String,
        val body: String,
        val authorization: String?,
        val key: String?,
    )

    private val received = ArrayList<Received>()
    private var status = 202
    private var answer = "{}"
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/internal/v1/tenancy/tenants") { exchange ->
                received +=
                    Received(
                        exchange.requestURI.path,
                        String(exchange.requestBody.readAllBytes()),
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Idempotency-Key"),
                    )
                val bytes = answer.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/problem+json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun closer() =
        HttpTenantCloser(
            URI.create("http://127.0.0.1:${server.address.port}"),
            WorkloadTokenSource { WorkloadToken("w") },
            Duration.ofSeconds(2),
        )

    private fun schedule(
        notice: Int?,
        mobile: String? = null,
    ) = TenantSchedule(
        CommercialRef("TEN-7B1004"),
        "SCH-7A21C4",
        CommercialRef("CTR-1"),
        ScheduleState.APPROVED,
        Instant.parse("2026-12-01T01:00:00Z"),
        Instant.parse("2027-12-01T01:00:00Z"),
        "alice",
        "bob",
        CustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example", mobile),
        notice,
        Instant.parse("2026-12-01T01:00:00Z"),
    )

    @Test
    fun `INV-AC-8177 closeTenant is a POST to the close path with the recorded custody holder, no override and no end-user token`() {
        closer().close(CommercialRef("TEN-7B1004"), schedule(30)) shouldBe TenantClosing.Closed
        val r = received.single()
        r.path shouldBe "/internal/v1/tenancy/tenants/TEN-7B1004/close"
        r.body shouldBe
            """{"scheduleRef":"SCH-7A21C4","retentionCustodyHolder":{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example"},"noticeWindowDays":30}"""
        r.authorization shouldBe null
        r.body.contains("secondConfirmationBy") shouldBe false
        r.body.contains("unsubmittedOverride") shouldBe false
    }

    @Test
    fun `INV-AC-8177 the idempotency key derives from the scheduleRef and is the same on a repeat`() {
        closer().close(CommercialRef("TEN-7B1004"), schedule(null))
        closer().close(CommercialRef("TEN-7B1004"), schedule(null, "+6591234567"))
        received.map { it.key }.distinct().size shouldBe 1
        received[0].body.contains("noticeWindowDays") shouldBe false
        received[1].body.contains("\"contactMobile\":\"+6591234567\"") shouldBe true
    }

    @Test
    fun `INV-AC-8177 a 409 refusal carries its detail and an unreachable tenancy-svc is not a closing`() {
        status = 409
        answer = """{"status":409,"code":"TNC-009","detail":"an in-scope document is unsubmitted"}"""
        closer().close(CommercialRef("TEN-2"), schedule(null)) shouldBe
            TenantClosing.NotClosed("an in-scope document is unsubmitted")
        answer = "not json"
        closer().close(CommercialRef("TEN-2"), schedule(null)) shouldBe
            TenantClosing.NotClosed("closeTenant answered 409")
        server.stop(0)
        (closer().close(CommercialRef("TEN-2"), schedule(null)) is TenantClosing.NotClosed) shouldBe true
    }
}
