package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.payload.api.ValidationApi
import com.dynokore.gst.consoleBff.adapters.clients.payload.model.OverrideValidationRequest
import com.dynokore.gst.consoleBff.adapters.clients.submission.api.ReadinessApi
import com.dynokore.gst.consoleBff.adapters.clients.submission.model.FilingGateOverrideRequest
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.openapitools.client.infrastructure.RequestMethod
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** INV-AC-21173: the one place that names the edges, base URLs and timeouts of the payload and submission clients. */
class OwningServiceEdgeTest {
    private companion object {
        const val TIMEOUT_MS = 2000L
    }

    private val placeholder = "$" + "{CONSOLE_PAYLOAD_SVC_URL}"

    private fun env(
        property: String,
        value: String?,
    ) = MockEnvironment().also { if (value != null) it.setProperty(property, value) }

    private fun forEachService(check: (OwningService) -> Unit) = OwningService.entries.forEach(check)

    @Test
    @DisplayName("INV-AC-21173 (a) baseUrl throws for an absent property of each service")
    fun absentProperty() =
        forEachService { s ->
            assertThrows(IllegalStateException::class.java) { OwningServiceEdge.baseUrl(MockEnvironment(), s) }
        }

    @Test
    @DisplayName("INV-AC-21173 (b) baseUrl throws for a blank value of each service")
    fun blankValue() =
        forEachService { s ->
            for (blank in listOf("", " ", "\t")) {
                assertThrows(IllegalArgumentException::class.java) {
                    OwningServiceEdge.baseUrl(env(s.urlProperty, blank), s)
                }
            }
        }

    @Test
    @DisplayName("INV-AC-21173 (c) baseUrl throws for an unresolved placeholder of each service")
    fun unresolvedPlaceholder() =
        forEachService { s ->
            assertThrows(IllegalArgumentException::class.java) {
                OwningServiceEdge.baseUrl(env(s.urlProperty, placeholder), s)
            }
        }

    @Test
    @DisplayName("INV-AC-21173 (c2) baseUrl throws for a raw placeholder the environment hands back unresolved")
    fun rawPlaceholderFromEnvironment() =
        forEachService { s ->
            // an Environment that does not resolve nested placeholders returns the text as written
            val raw =
                object : MockEnvironment() {
                    override fun getRequiredProperty(key: String): String = placeholder
                }
            assertThrows(IllegalArgumentException::class.java) { OwningServiceEdge.baseUrl(raw, s) }
        }

    @Test
    @DisplayName("INV-AC-21173 (d) baseUrl returns the set value unchanged for each service")
    fun setValue() =
        forEachService { s ->
            assertEquals(
                "http://host-${s.name}:8080/x",
                OwningServiceEdge.baseUrl(env(s.urlProperty, "http://host-${s.name}:8080/x"), s),
            )
        }

    @Test
    @DisplayName("INV-AC-21173 (e) the property of one service does not satisfy the other")
    fun propertiesAreSeparate() {
        assertThrows(IllegalStateException::class.java) {
            OwningServiceEdge.baseUrl(env("console.submission-svc-url", "http://a"), OwningService.PAYLOAD)
        }
        assertThrows(IllegalStateException::class.java) {
            OwningServiceEdge.baseUrl(env("console.payload-svc-url", "http://a"), OwningService.SUBMISSION)
        }
    }

    @Test
    @DisplayName("INV-AC-21173 (f) the property names are console.payload-svc-url and console.submission-svc-url")
    fun propertyNames() {
        assertEquals("console.payload-svc-url", OwningService.PAYLOAD.urlProperty)
        assertEquals("console.submission-svc-url", OwningService.SUBMISSION.urlProperty)
    }

    @Test
    @DisplayName("INV-AC-21173 (g) the edge names are those of x-sync-edge in the pinned contracts")
    fun edgeNames() {
        assertEquals("console-bff->payload-svc", OwningService.PAYLOAD.edge)
        assertEquals("console-bff->submission-svc", OwningService.SUBMISSION.edge)
    }

    @Test
    @DisplayName(
        "INV-AC-21173 (h) the timeouts are 2000 ms, the x-timeout of overrideValidation and overrideFilingGate",
    )
    fun timeouts() {
        assertEquals(Duration.ofMillis(TIMEOUT_MS), OwningService.PAYLOAD.timeout)
        assertEquals(Duration.ofMillis(TIMEOUT_MS), OwningService.SUBMISSION.timeout)
    }

    @Test
    @DisplayName("INV-AC-21173 (i) restClient sends its request to the stub host with the path it is given")
    fun restClientSends() {
        val seen = CopyOnWriteArrayList<String>()
        val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { x ->
                    seen += x.requestMethod + " " + x.requestURI.path
                    x.sendResponseHeaders(204, -1)
                    x.close()
                }
                start()
            }
        try {
            forEachService { s ->
                val url = "http://127.0.0.1:${server.address.port}"
                val client: RestClient =
                    OwningServiceEdge.restClient(RestClient.builder(), env(s.urlProperty, url), s)
                client
                    .get()
                    .uri("/probe/${s.name}")
                    .retrieve()
                    .toBodilessEntity()
            }
        } finally {
            server.stop(0)
        }
        assertEquals(listOf("GET /probe/PAYLOAD", "GET /probe/SUBMISSION"), seen.toList())
    }

    private class Recording : ResilienceRegistry {
        val asked = mutableListOf<String>()

        override fun forEdge(edge: String): ResilientEdge {
            asked += edge
            return object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = block()
            }
        }
    }

    @Test
    @DisplayName("INV-AC-21173 (j) policy asks the registry for exactly the edge of the service and no other")
    fun policyAsksOneEdge() {
        forEachService { s ->
            val registry = Recording()
            OwningServiceEdge.policy(registry, s)
            assertEquals(listOf(s.edge), registry.asked)
        }
    }

    @Test
    @DisplayName("INV-AC-21173 (k) the generated request configurations are POST with the contract paths")
    fun generatedRequestConfigs() {
        val client = RestClient.builder().baseUrl("http://127.0.0.1:1").build()
        val payload =
            ValidationApi(client).overrideValidationRequestConfig(
                UUID(0, 0),
                1,
                OverrideValidationRequest(code = "X", reason = "r"),
            )
        assertEquals(RequestMethod.POST, payload.method)
        assertEquals("/internal/v1/payload/documents/{docUuid}/versions/{version}/overrides", payload.path)
        val submission =
            ReadinessApi(client).overrideFilingGateRequestConfig(
                "e",
                "p",
                FilingGateOverrideRequest(checkId = "c", reason = "r"),
            )
        assertEquals(RequestMethod.POST, submission.method)
        assertEquals("/internal/v1/entities/{entityRef}/periods/{periodRef}/filing-gate-overrides", submission.path)
    }
}
