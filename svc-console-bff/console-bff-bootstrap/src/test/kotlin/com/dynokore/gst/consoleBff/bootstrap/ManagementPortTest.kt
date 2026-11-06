package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
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
import java.nio.file.Path

/**
 * API-33 and TPL-16 (P109): the running service, over the Testcontainers database, serves health, readiness and
 * metrics on the management port and nothing of them on the application port. The application port is ephemeral; the
 * management port is the one application.yaml sets (8081), asserted here, so a yaml that equals the two fails.
 */
class ManagementPortTest {
    private val http = HttpClient.newHttpClient()

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `INV-AC-14435 health readiness and metrics are served on the management port only`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.secrets(dir)
        val app =
            SpringApplicationBuilder(ConsoleBffApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(
                    *ConsoleContext.overrides(
                        dir,
                        "server.port=0",
                        "spring.datasource.url=${ConsoleDb.jdbcUrl}",
                    ),
                ).run()
        app.use { context ->
            val management = context.environment.getProperty("local.management.port")!!
            val main = context.environment.getProperty("local.server.port")!!
            management shouldBe "8081" // the unmodified application.yaml port
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
