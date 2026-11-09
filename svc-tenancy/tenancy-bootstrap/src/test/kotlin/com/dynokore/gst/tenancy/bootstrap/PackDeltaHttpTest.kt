package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * getCalendarPackDelta over HTTP through the filter chain of service-runtime (INV-T-16829; INV-AC-16824): the missing
 * required parameter is observed at the boundary, not assumed from the framework. The 400 body of an absent
 * parameter is the framework default (status, error, path), not the API-20 envelope: reported as a gap.
 */
class PackDeltaHttpTest {
    private val http = HttpClient.newHttpClient()

    private fun get(
        dir: Path,
        query: String,
    ): HttpResponse<String> {
        var answer: HttpResponse<String>? = null
        BoundaryFixture
            .startServlet(
                dir,
                listOf(EntityFactsIdentity::class.java),
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                    "management.server.port=0",
                ),
                javaClass.classLoader,
            ).use { context ->
                val env = ProfileEnv(context)
                env.withEntity { tenant, entity ->
                    EntityFactsIdentity.principal =
                        EndUserPrincipal("user-a", tenant, setOf(EntityRef(entity.entityRef)))
                    val port = context.environment.getProperty("local.server.port")
                    val request =
                        HttpRequest
                            .newBuilder(
                                URI.create(
                                    "http://localhost:$port/internal/v1/tenancy/entities/${entity.entityRef}" +
                                        "/calendar-pack-deltas$query",
                                ),
                            ).header("X-Workload-Token", "token-console-bff")
                            .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
                            .GET()
                            .build()
                    answer = http.send(request, HttpResponse.BodyHandlers.ofString())
                }
            }
        return answer!!
    }

    @Test
    fun `INV-AC-16824 a request without packIdentifier is 400 over HTTP and discloses no period`(
        @TempDir dir: Path,
    ) {
        val refused = get(dir, "?version=1")
        assertThat(refused.statusCode()).describedAs(refused.body()).isEqualTo(400)
        assertThat(refused.body()).doesNotContain("periodRef")
    }

    @Test
    fun `INV-AC-16824 a request without version is 400 over HTTP and discloses no period`(
        @TempDir dir: Path,
    ) {
        val refused = get(dir, "?packIdentifier=P1")
        assertThat(refused.statusCode()).describedAs(refused.body()).isEqualTo(400)
        assertThat(refused.body()).doesNotContain("periodRef")
    }
}
