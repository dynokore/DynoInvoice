package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.InstallerCallFilter
import com.dynokore.gst.tenancy.syncedge.TestIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * The filter of recordInstallationAction through the registered servlet filter and a real HTTP call (P109; INV-T-3290,
 * INV-Q-3826 A, SRT-003, SRT-004): the registration scopes it, it runs after the workload-token filter (-90), and a
 * raw-URI variant of the path (path parameter, doubled slash, dot segment, trailing slash) is refused like the plain
 * one.
 */
class InstallerCallFilterWiringTest {
    private val body =
        """{"runId":"0b0c9a3e-6d1f-4f0e-9a52-7a1d3c5e8f10","step":1,"command":"ROTATE_DB_CREDENTIAL",""" +
            """"stepName":"verify-backup-target","outcome":"SUCCEEDED","occurredAt":"2026-10-16T08:30:00Z",""" +
            """"runIdentity":"cli:ops-lee","deploymentOperator":"ops-lee"}"""

    private fun serve(
        dir: Path,
        subject: String,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val properties =
            arrayOf(
                "inv.installer.subject=$subject",
                "service-runtime.workload-identity.callers[0].service=installer",
                "service-runtime.workload-identity.callers[0].subject=sa-installer",
                "service-runtime.workload-identity.callers[1].service=other",
                "service-runtime.workload-identity.callers[1].subject=sa-other",
            )
        BoundaryFixture
            .startServlet(dir, listOf(TestIdentity::class.java), properties, javaClass.classLoader)
            .use(block)
    }

    private fun post(
        ctx: ConfigurableApplicationContext,
        path: String,
        headers: Map<String, String>,
    ): Int {
        val port = ctx.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "7d1c6b7e-0f6a-4b8e-9d57-3a1f2c4e5b60")
        headers.forEach { (name, value) -> request.header(name, value) }
        return HttpClient
            .newHttpClient()
            .send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            .statusCode()
    }

    private val token = "X-Workload-Token"

    @Test
    fun `INV-AC-3828 the filter is registered after the workload filter and refuses every path variant`(
        @TempDir dir: Path,
    ) {
        serve(dir, "sa-installer") { ctx ->
            @Suppress("UNCHECKED_CAST")
            val registration = ctx.getBean("installerCallFilter") as FilterRegistrationBean<InstallerCallFilter>
            assertThat(registration.urlPatterns)
                .containsExactlyInAnyOrder(InstallerCallFilter.PATH, InstallerCallFilter.PATH + "/*")
            assertThat(registration.order).isGreaterThan(WORKLOAD_FILTER_ORDER)
            val installer = mapOf(token to "token-installer")
            assertThat(post(ctx, InstallerCallFilter.PATH, installer)).isBetween(200, 299)
            val variants =
                listOf(
                    InstallerCallFilter.PATH,
                    InstallerCallFilter.PATH + ";x=1",
                    "/" + InstallerCallFilter.PATH,
                    InstallerCallFilter.PATH.replace("tenancy/", "tenancy/./"),
                    InstallerCallFilter.PATH + "/",
                )
            variants.forEach { path ->
                assertThat(
                    post(ctx, path, mapOf(token to "token-other")),
                ).describedAs("other workload $path").isIn(403, 404)
                assertThat(post(ctx, path, emptyMap())).describedAs("no token $path").isIn(401, 404)
                assertThat(post(ctx, path, installer + ("Authorization" to "Bearer u")))
                    .describedAs("end-user token $path")
                    .isIn(403, 404)
            }
            assertThat(post(ctx, InstallerCallFilter.PATH, mapOf(token to "token-other"))).isEqualTo(403)
            assertThat(post(ctx, InstallerCallFilter.PATH, emptyMap())).isEqualTo(401)
            assertThat(post(ctx, InstallerCallFilter.PATH, installer + ("Authorization" to "Bearer u"))).isEqualTo(403)
            assertThat(post(ctx, InstallerCallFilter.PATH + ";x=1", mapOf(token to "token-other"))).isEqualTo(403)
            assertThat(post(ctx, "/" + InstallerCallFilter.PATH, mapOf(token to "token-other"))).isEqualTo(403)
        }
    }

    @Test
    fun `INV-AC-3828 a blank installer subject refuses every caller through the registered filter`(
        @TempDir dir: Path,
    ) {
        serve(dir, "") { ctx ->
            assertThat(post(ctx, InstallerCallFilter.PATH, mapOf(token to "token-installer"))).isEqualTo(403)
        }
    }

    private companion object {
        const val WORKLOAD_FILTER_ORDER = -90
    }
}
