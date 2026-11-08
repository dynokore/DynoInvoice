package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID

/**
 * recordMigrationCutover and getMigrationCutover over HTTP through the filter chain of service-runtime (INV-REQ-1109):
 * the fail-closed 403 of the production authority binding with no replica, the 401 without an end-user principal and
 * the 400 of a request that lacks a required property.
 */
class MigrationCutoverHttpTest {
    private val http = HttpClient.newHttpClient()

    private fun serve(
        dir: Path,
        block: (ProfileEnv) -> Unit,
    ) {
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
            ).use { block(ProfileEnv(it)) }
    }

    private fun ProfileEnv.call(
        method: String,
        entityRef: String,
        body: String?,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val builder =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:$port/internal/v1/tenancy/entities/$entityRef/migration-cutover"),
                ).header("Content-Type", "application/json")
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
        if (body != null) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString())
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun granted(
        tenant: TenantId,
        entityRef: String,
    ) {
        EntityFactsIdentity.principal = EndUserPrincipal("user-a", tenant, setOf(EntityRef(entityRef)))
    }

    private fun body(
        date: String = "2026-10-01",
        priorSolution: String = "LegacyPeppolProvider",
    ) = """{"cutoverDate":"$date","priorSolution":"$priorSolution"}"""

    @Test
    fun `INV-AC-2107 over HTTP with no replica bound both operations answer 403 and a call without a principal 401`(
        @TempDir dir: Path,
    ) {
        serve(dir) { env ->
            env.withEntity { tenant, entity ->
                granted(tenant, entity.entityRef)
                val record = env.call("POST", entity.entityRef, body())
                assertThat(record.statusCode()).describedAs(record.body()).isEqualTo(403)
                val read = env.call("GET", entity.entityRef, null)
                assertThat(read.statusCode()).describedAs(read.body()).isEqualTo(403)
                val count =
                    BoundaryFixture.asSuperuser { c ->
                        c.prepareStatement("select count(*) from tenancy.migration_cutover").use { s ->
                            s.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
                        }
                    }
                assertThat(count).isZero()
                EntityFactsIdentity.principal = null
                assertThat(env.call("POST", entity.entityRef, body()).statusCode()).isEqualTo(401)
                assertThat(env.call("GET", entity.entityRef, null).statusCode()).isEqualTo(401)
            }
        }
    }

    @Test
    fun `INV-AC-2107 over HTTP a body without priorSolution is 400 before the permission check`(
        @TempDir dir: Path,
    ) {
        serve(dir) { env ->
            env.withEntity { tenant, entity ->
                granted(tenant, entity.entityRef)
                val missing = env.call("POST", entity.entityRef, """{"cutoverDate":"2026-10-01"}""")
                assertThat(missing.statusCode()).describedAs(missing.body()).isEqualTo(400)
            }
        }
    }
}
