package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.domain.ElectionRoles
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
import java.util.UUID

/**
 * The ports of the documents count and of the export are unbound in this context (the default binding, coordinator
 * ruling INV-Q-4900): offboardTenant is refused 422 POL-014 naming the unavailable input and stores nothing.
 */
class OffboardingUnboundHttpTest {
    private fun count(): Long =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from tenancy.offboarding_record").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    @Test
    fun `probe the ports unbound is 422 POL-014 naming the input and nothing is stored`(
        @TempDir dir: Path,
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
            ).use { context ->
                val env = ProfileEnv(context)
                env.withEntity { tenant, entity ->
                    val people = context.getBean(TestPeople::class.java)
                    people.caller = "identity:USR-004"
                    people.grant("identity:USR-004", entity.entityRef, ElectionRoles.ENTITY_ADMIN)
                    people.grant("identity:USR-0040", entity.entityRef, ElectionRoles.TAX_APPROVER)
                    EntityFactsIdentity.principal =
                        EndUserPrincipal("user-a", tenant, setOf(EntityRef(entity.entityRef)))
                    val before = count()
                    val port = context.environment.getProperty("local.server.port")
                    val body =
                        """{"scope":"ENTITY","entityRef":"${entity.entityRef}",""" +
                            """"secondConfirmationBy":"identity:USR-0040",""" +
                            """"retentionCustodyHolder":{"name":"Ledger","contactEmail":"a@b.example"}}"""
                    val url = "http://localhost:$port/internal/v1/tenancy/tenants/${tenant.value}/offboarding"
                    val request =
                        HttpRequest
                            .newBuilder(URI.create(url))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .header("X-Workload-Token", "token-console-bff")
                            .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build()
                    val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(422)
                    assertThat(answer.body()).contains("POL-014").contains("unsubmitted-documents-count")
                    assertThat(count()).isEqualTo(before)
                }
            }
    }
}
