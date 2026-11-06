package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.domain.commercial.FirstAdministratorOutcome
import com.dynokore.gst.controlPlane.syncedge.ReissueDoubles
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID

/**
 * The stored `first_administrator_outcome` is internal (INV-AC-8224, INV-Q-17793 A): the HTTP bodies of
 * `provisionTenant` and `getTenantProvisioningRequest` carry neither the column name nor its camelCase form, whatever
 * outcome is stored. The assertions read the response body of the real application over HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvisioningBodyOutcomeTest {
    private lateinit var context: ConfigurableApplicationContext
    private val doubles get() = context.getBean(ReissueDoubles::class.java)

    @BeforeAll
    fun start(
        @TempDir dir: Path,
    ) {
        val properties =
            arrayOf(
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
            )
        context = ServletFixture.start(dir, listOf(ReissueDoubles::class.java), properties, javaClass.classLoader)
    }

    @AfterAll
    fun stop() = context.close()

    @BeforeEach
    fun reset() {
        doubles.permitted.clear()
        doubles.permitted += "carol"
    }

    private fun get(): HttpResponse<String> = call("GET", "/${doubles.contract.value}", null)

    private fun post(): HttpResponse<String> =
        call(
            "POST",
            "",
            """{"contractReference":"${doubles.contract.value}","legalName":"Acme","planRef":"P",""" +
                """"firstAdministratorEmail":"admin@acme.example","dataRegion":"SG"}""",
        )

    private fun call(
        method: String,
        suffix: String,
        body: String?,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$ROOT$suffix"))
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer user-carol")
                .header("X-Correlation-Id", "corr-1")
                .apply {
                    if (body != null) {
                        header("Content-Type", "application/json")
                        header("Idempotency-Key", UUID.randomUUID().toString())
                    }
                }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertNoOutcome(answer: HttpResponse<String>) {
        answer.body() shouldNotContain "first_administrator_outcome"
        answer.body() shouldNotContain "firstAdministratorOutcome"
        answer.body() shouldNotContain FirstAdministratorOutcome.TENANT_ALREADY_HAS_ADMINISTRATOR.name
    }

    @Test
    fun `INV-AC-8224 the provisionTenant and getTenantProvisioningRequest bodies carry no first administrator outcome`() {
        FirstAdministratorOutcome.entries.forEach { outcome ->
            doubles.outcome = outcome
            val read = get()
            read.statusCode() shouldBe 200
            read.body() shouldNotContain "contractReference\":null"
            assertNoOutcome(read)
            val created = post()
            created.statusCode() shouldBe 201
            assertNoOutcome(created)
        }
    }

    private companion object {
        const val ROOT = "/internal/v1/control-plane/tenant-provisioning-requests"
    }
}
