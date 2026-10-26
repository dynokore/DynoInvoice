package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.IngestionDb
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * API-33 and TPL-16 (P109): the running service, over the Testcontainers database, serves health, readiness and
 * metrics on the management port and nothing of them on the application port. Both ports are ephemeral so parallel
 * runs cannot collide; the fixed 8081 is asserted by Tpl16ServiceCompositionTest.
 */
class ManagementPortTest {
    private val http = HttpClient.newHttpClient()

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `health readiness and metrics are served on the management port only`(
        @TempDir dir: Path,
    ) {
        Files.createDirectories(dir.resolve("db"))
        Files.createDirectories(dir.resolve("tenancy"))
        Files.writeString(dir.resolve("db/ingestion_runtime"), IngestionDb.RUNTIME_PASSWORD)
        Files.writeString(dir.resolve("tenancy/binding-key-current"), IngestionDb.BINDING_SECRET)
        val app =
            SpringApplicationBuilder(IngestionApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(
                    "server.port=0",
                    "management.server.port=0",
                    "spring.datasource.url=${IngestionDb.jdbcUrl}",
                    "spring.datasource.username=ingestion_runtime",
                    "service-runtime.workload-identity.audience=ingestion-svc",
                    "gst.service=ingestion-svc",
                    "gst.instance-id=ingestion-svc-0",
                    "service-runtime.pool.profile=minimal",
                    "service-runtime.modules.schema=ingestion",
                    "service-runtime.idempotency.schema=ingestion",
                    "service-runtime.datasource.credential=db/ingestion_runtime",
                    "gst.bindings.secret-store.mount-root=$dir",
                ).run()
        app.use { context ->
            val management = context.environment.getProperty("local.management.port")!!
            val main = context.environment.getProperty("local.server.port")!!
            (management == main) shouldBe false
            val base = "http://localhost:$management"
            get("$base/health").statusCode() shouldBe 200
            get("$base/health/liveness").statusCode() shouldBe 200
            get("$base/health/ready").statusCode() shouldBe 200
            val metrics = get("$base/metrics")
            metrics.statusCode() shouldBe 200
            metrics.body() shouldContain "jvm_"
            // the application port answers 401 (no token) or 404, never the management payload
            get("http://localhost:$main/health").statusCode() shouldBeIn listOf(401, 404)
            get("http://localhost:$main/metrics").statusCode() shouldBeIn listOf(401, 404)
        }
    }
}
