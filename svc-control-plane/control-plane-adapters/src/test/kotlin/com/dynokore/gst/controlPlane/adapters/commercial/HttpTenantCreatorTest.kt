package com.dynokore.gst.controlPlane.adapters.commercial

import com.dynokore.gst.controlPlane.adapters.tenancy.HttpTenantCreator
import com.dynokore.gst.controlPlane.application.commercial.CallerCredential
import com.dynokore.gst.controlPlane.application.commercial.CreateTenantCall
import com.dynokore.gst.controlPlane.application.commercial.TenantCreation
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration

/** The request that the tenancy contract stub receives from provisionTenant for createTenant (INV-Q-8675 A). */
class HttpTenantCreatorTest {
    private val received = ArrayList<Pair<Map<String, Any?>, Map<String, String>>>()
    private var status = 201
    private var delayMillis = 0L
    private val json = JsonMapper.builder().build()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(HttpTenantCreator.PATH) { exchange ->
                @Suppress("UNCHECKED_CAST")
                val body = json.readValue(exchange.requestBody.readAllBytes(), Map::class.java) as Map<String, Any?>
                received +=
                    body to
                    mapOf(
                        "method" to exchange.requestMethod,
                        "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                        "key" to exchange.requestHeaders.getFirst("Idempotency-Key"),
                    )
                Thread.sleep(delayMillis)
                val answer =
                    """{"tenantRef":"TEN-1","legalName":"Acme","planRef":"P","dataRegion":"SG"}"""
                        .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun creator(timeout: Duration = Duration.ofSeconds(2)) =
        HttpTenantCreator(URI.create("http://127.0.0.1:${server.address.port}"), timeout)

    private fun call(planRef: String = "PLAN-STANDARD") =
        CreateTenantCall(
            CommercialRef("TEN-1"),
            CommercialRef("CTR-2026-0091"),
            "Acme Holdings Pte Ltd",
            PlanRef(planRef),
            RegionKey("SG"),
            CallerCredential("Bearer operator-token"),
        )

    @Test
    fun `INV-AC-8570 INV-AC-8572 the createTenant request carries dataRegion SG, the planRef and the tenantRef`() {
        creator().create(call(planRef = "P".repeat(64))) shouldBe TenantCreation.Created
        val (body, headers) = received.single()
        body["dataRegion"] shouldBe "SG"
        body["planRef"] shouldBe "P".repeat(64)
        body["tenantRef"] shouldBe "TEN-1"
        body["contractReference"] shouldBe "CTR-2026-0091"
        body.keys shouldBe setOf("contractReference", "legalName", "planRef", "tenantRef", "dataRegion")
        headers["method"] shouldBe "POST"
        headers["authorization"] shouldBe "Bearer operator-token"
    }

    @Test
    fun `the Idempotency-Key is the same on every attempt of one contract`() {
        creator().create(call())
        creator().create(call())
        received.map { it.second["key"] }.toSet().size shouldBe 1
    }

    @Test
    fun `a creation that outlasts its deadline answers Pending`() {
        delayMillis = 800
        creator(Duration.ofMillis(200)).create(call()) shouldBe TenantCreation.Pending
    }

    @Test
    fun `a non-2xx answer and an unreachable tenancy-svc answer Pending, never Created`() {
        status = 500
        creator().create(call()) shouldBe TenantCreation.Pending
        server.stop(0)
        creator().create(call()) shouldBe TenantCreation.Pending
    }
}
