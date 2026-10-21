package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.application.AttemptLog
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-7855 over HTTP: the failure rate metric of an adapter is scraped from the management server (the port is 8081
 * in `application.yaml`, asserted by the TPL-16 test; here it is an ephemeral port so parallel runs do not collide).
 */
class ManagementScrapeTest {
    private fun scrape(url: String): String =
        HttpClient
            .newHttpClient()
            .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
            .body()

    private fun start(
        dir: Path,
        adapter: String,
    ): ConfigurableApplicationContext {
        TransportContext.secrets(dir)
        return SpringApplicationBuilder(TransportApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .properties(*TransportContext.propertyValues(dir, TransportDb.jdbcUrl).toTypedArray())
            .properties(
                "server.port=0",
                "management.server.port=0",
                "transport.ap.adapters[0].id=$adapter",
                "transport.ap.adapters[0].path=primary",
            ).run()
    }

    private fun ConfigurableApplicationContext.record(
        adapter: String,
        outcomes: List<TransportOutcome>,
    ) {
        val scope = getBean(TenantScope::class.java)
        val attempts = getBean(AttemptLog::class.java)
        outcomes.forEach { outcome ->
            scope.bind(TenantId(UUID.randomUUID()), "svc:transport-svc") {
                val doc = DocRef(UUID.randomUUID(), 1)
                val id = attempts.begin(EntityRef("E-001"), doc, AdapterId(adapter), Instant.now())
                attempts.complete(id, outcome, Instant.now())
            }
        }
    }

    @Test
    fun `INV-AC-7855 the failure rate is scraped on the management server and equals failed over attempts`(
        @TempDir dir: Path,
    ) {
        val adapter = "m-${UUID.randomUUID().toString().take(UNIQUE)}"
        val app = start(dir, adapter)
        try {
            val accepted = TransportOutcome.Accepted("a")
            app.record(adapter, listOf(accepted, TransportOutcome.TransientFailure, accepted, accepted))
            val text = scrape("http://localhost:${app.environment.getProperty("local.management.port")}/metrics")
            val line = text.lines().first { it.startsWith("transport_ap_failure_rate") && it.contains("\"$adapter\"") }
            line.substringAfterLast(' ').toDouble() shouldBe 0.25
        } finally {
            app.close()
        }
    }

    private companion object {
        const val UNIQUE = 12
    }
}
