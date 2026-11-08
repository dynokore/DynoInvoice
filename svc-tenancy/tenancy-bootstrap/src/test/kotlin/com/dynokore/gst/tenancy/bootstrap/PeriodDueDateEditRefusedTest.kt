package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.api.PeriodsApi
import com.dynokore.gst.tenancy.syncedge.TestIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * A due date is never edited directly (INV-REQ-81, INV-AC-107 API part, INV-AC-16067): the contract has no operation
 * that takes a due date for a period resource, so a PATCH or a PUT of one is refused by the API with a 4xx response of
 * the error envelope, and the generated interface offers no other writer of a due date than recordExtension.
 */
class PeriodDueDateEditRefusedTest {
    @Test
    fun `INV-AC-16067 INV-AC-107 a PATCH and a PUT carrying a dueDate for a period are refused 4xx and change nothing`(
        @TempDir dir: Path,
    ) {
        BoundaryFixture
            .startServlet(
                dir,
                listOf(TestIdentity::class.java),
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                ),
                javaClass.classLoader,
            ).use { context ->
                val port = context.environment.getProperty("local.server.port")
                val periodResource = "http://localhost:$port/internal/v1/tenancy/entities/ENT-1/periods/2026Q2"
                listOf("PATCH", "PUT").forEach { method ->
                    val request =
                        HttpRequest
                            .newBuilder(URI.create(periodResource))
                            .header("Content-Type", "application/json")
                            .header("X-Workload-Token", "token-console-bff")
                            .header("Authorization", "Bearer user-a-token")
                            .method(method, HttpRequest.BodyPublishers.ofString("{\"dueDate\":\"2026-08-31\"}"))
                            .build()
                    val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    assertThat(answer.statusCode()).describedAs(method).isBetween(400, 499)
                    assertThat(
                        answer.headers().firstValue("Content-Type").orElse(""),
                    ).describedAs(method).contains("json")
                    assertThat(answer.body()).describedAs(method).contains("\"code\"")
                }
            }
    }

    @Test
    fun `the only operations of the periods API that take a date are confirmPeriods, recordExtension and the read`() {
        val operations =
            PeriodsApi::class.java.methods
                .filter {
                    it.declaringClass == PeriodsApi::class.java &&
                        !it.isSynthetic
                }.map { it.name }
        assertThat(
            operations,
        ).containsExactlyInAnyOrder("confirmPeriods", "getCalendarPackDelta", "listPeriodRecords", "recordExtension")
    }
}
