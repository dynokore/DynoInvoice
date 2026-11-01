package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.ap.AccessPointReadException
import com.dynokore.gst.transport.adapters.ap.ApEndpoints
import com.dynokore.gst.transport.adapters.ap.HttpAccessPointAdapter
import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.NotificationReason
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** The notification-source port of the HTTP adapter, on recorded pages of the AP simulator's published shape. */
class ApNotificationSourceTest {
    private val adapter = AdapterId("primary")
    private val doc = "00000000-0000-4000-8000-000000000001"
    private val servers = mutableListOf<HttpServer>()
    private val requested = AtomicReference<String>()

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    private fun item(
        id: String,
        type: String,
        extra: String = "",
    ) = """{"notificationId":"$id","adapterId":"primary","type":"$type","at":"2026-01-01T00:00:02Z",
        |"apSubmissionId":"AP-000001","docUuid":"$doc","version":1$extra}
        """.trimMargin()

    private fun adapterOver(
        status: Int = 200,
        body: String = "",
        delayMillis: Long = 0,
        timeout: Duration = Duration.ofSeconds(5),
        unknown: MutableList<String?> = mutableListOf(),
    ): HttpAccessPointAdapter {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requested.set(exchange.requestURI.toString())
            Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        val endpoints =
            ApEndpoints(IrasEnvironment.SANDBOX, URI.create("http://127.0.0.1:${server.address.port}"), null)
        return HttpAccessPointAdapter(
            adapter,
            endpoints,
            "primary",
            timeout,
            onUnknownType = { id, _ -> unknown += id },
        )
    }

    private fun page(
        vararg items: String,
        next: String? = ",\"nextCursor\":4",
    ) = """{"notifications":[${items.joinToString(",")}]${next.orEmpty()}}"""

    @Test
    fun `an IRAS acknowledgement maps to the kind ACKNOWLEDGEMENT`() {
        val result =
            adapterOver(body = page(item("N-1", "IRAS_ACKNOWLEDGEMENT", ",\"acknowledgementId\":\"IRAS-ACK-1\"")))
                .pageAfter(adapter, null)
        val n = result.items.single()
        n.kind shouldBe "ACKNOWLEDGEMENT"
        n.apNotificationId shouldBe "N-1"
        n.docUuid shouldBe UUID.fromString(doc)
        n.version shouldBe 1
        n.acknowledgementId shouldBe "IRAS-ACK-1"
    }

    @Test
    fun `an IRAS rejection maps to the kind REJECTION with its reasons in order`() {
        val reasons =
            ",\"reasons\":[{\"code\":\"A-1\",\"message\":\"first\"}," +
                "{\"code\":\"B-2\",\"message\":\"second\"}]"
        val n = adapterOver(body = page(item("N-2", "IRAS_REJECTION", reasons))).pageAfter(adapter, null).items.single()
        n.kind shouldBe "REJECTION"
        n.reasons shouldBe listOf(NotificationReason("A-1", "first"), NotificationReason("B-2", "second"))
    }

    @Test
    fun `a delivery status maps to the kind DELIVERY_STATUS`() {
        val n =
            adapterOver(body = page(item("N-3", "DELIVERY_STATUS", ",\"deliveryStatus\":\"DELIVERED\"")))
                .pageAfter(adapter, null)
                .items
                .single()
        n.kind shouldBe "DELIVERY_STATUS"
    }

    @Test
    fun `an inbound document maps to the kind INBOUND_NETWORK_DOCUMENT`() {
        val n = adapterOver(body = page(item("N-4", "INBOUND_DOCUMENT"))).pageAfter(adapter, null).items.single()
        n.kind shouldBe "INBOUND_NETWORK_DOCUMENT"
    }

    @Test
    fun `an unknown type is reported by notification id and skipped`() {
        val unknown = mutableListOf<String?>()
        val result =
            adapterOver(
                body = page(item("N-5", "SOMETHING_NEW"), item("N-6", "DELIVERY_STATUS")),
                unknown = unknown,
            ).pageAfter(adapter, null)
        unknown shouldBe listOf("N-5")
        result.items.map { it.apNotificationId } shouldBe listOf("N-6")
    }

    @Test
    fun `the request carries the cursor as after and the page limit`() {
        adapterOver(body = page(item("N-7", "DELIVERY_STATUS"))).also { it.pageAfter(adapter, "12") }
        requested.get() shouldBe "/ap/v1/primary/notifications?after=12&limit=50"
    }

    @Test
    fun `a page with a nextCursor hands it on`() {
        adapterOver(body = page(item("N-8", "DELIVERY_STATUS"))).pageAfter(adapter, "2").nextCursor shouldBe "4"
    }

    @Test
    fun `a page with no nextCursor keeps the cursor`() {
        val source = adapterOver(body = page(item("N-9", "DELIVERY_STATUS"), next = null))
        source.pageAfter(adapter, "7").nextCursor shouldBe "7"
    }

    @Test
    fun `an AP answer of 500 is a failed read that throws`() {
        shouldThrow<AccessPointReadException> { adapterOver(status = 500, body = "{}").pageAfter(adapter, "7") }
    }

    @Test
    fun `a timeout is a failed read that throws`() {
        shouldThrow<AccessPointReadException> {
            adapterOver(
                body = page(),
                delayMillis = 1500,
                timeout = Duration.ofMillis(200),
            ).pageAfter(adapter, "7")
        }
    }

    @Test
    fun `a body that is not a page is a failed read that throws`() {
        shouldThrow<AccessPointReadException> { adapterOver(body = "<html>").pageAfter(adapter, null) }
        shouldThrow<AccessPointReadException> { adapterOver(body = """{"nextCursor":3}""").pageAfter(adapter, null) }
    }
}
