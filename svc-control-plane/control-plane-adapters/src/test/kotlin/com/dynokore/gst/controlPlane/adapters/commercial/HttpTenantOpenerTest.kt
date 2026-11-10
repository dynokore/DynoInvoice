package com.dynokore.gst.controlPlane.adapters.commercial

import com.dynokore.gst.controlPlane.adapters.tenancy.HttpTenantOpener
import com.dynokore.gst.controlPlane.application.schedule.TenantOpening
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
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

/** The openTenant request that the tenancy contract stub receives from the scheduled job (INV-REQ-8100). */
class HttpTenantOpenerTest {
    private val received = ArrayList<Triple<String, String, String?>>()
    private var status = 200
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/internal/v1/tenancy/tenants") { exchange ->
                received +=
                    Triple(
                        exchange.requestURI.path,
                        String(exchange.requestBody.readAllBytes()),
                        exchange.requestHeaders.getFirst("Authorization"),
                    )
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun opener(timeout: Duration = Duration.ofSeconds(2)) =
        HttpTenantOpener(
            URI.create("http://127.0.0.1:${server.address.port}"),
            WorkloadTokenSource { WorkloadToken("w") },
            timeout,
        )

    @Test
    fun `INV-AC-8102 openTenant is a POST to the tenant open path with the scheduleRef and no end-user token`() {
        opener().open(CommercialRef("TEN-7B1004"), "SCH-1") shouldBe TenantOpening.OPENED
        received.single() shouldBe
            Triple("/internal/v1/tenancy/tenants/TEN-7B1004/open", """{"scheduleRef":"SCH-1"}""", null)
    }

    @Test
    fun `INV-AC-8102 a 409 of tenancy-svc or an unreachable tenancy-svc is not an opening`() {
        status = 409
        opener().open(CommercialRef("TEN-1"), "SCH-1") shouldBe TenantOpening.NOT_OPENED
        server.stop(0)
        opener().open(CommercialRef("TEN-1"), "SCH-1") shouldBe TenantOpening.NOT_OPENED
    }
}
