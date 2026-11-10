package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackStagedHandler
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * getCalendarPackDelta over HTTP through the filter chain of service-runtime (INV-T-16829; INV-AC-16824): the missing
 * required parameter is observed at the boundary, not assumed from the framework. Only the status is asserted for a
 * binding failure: its body is the framework default until the shared advice of INV-T-19581 is pinned (ruling).
 */
class PackDeltaHttpTest {
    private val http = HttpClient.newHttpClient()

    private val staged =
        "{\"packIdentifier\":\"sg-calendar\",\"packType\":\"calendar\",\"version\":\"2026.2\"," +
            "\"effectiveFrom\":\"2026-10-01\",\"minEngineVersion\":\"1.0.0\"," +
            "\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"s\",\"contents\":" +
            "{\"windows\":{\"QUARTERLY\":[{\"start\":\"2026-01-01\",\"end\":\"2026-03-31\",\"returnForm\":\"F5\"}]}," +
            "\"finalReturnForm\":\"F8\",\"dueRules\":{\"F5\":{\"monthsAfterEnd\":1,\"endOfMonth\":true," +
            "\"daysAfterEnd\":0},\"F8\":{\"monthsAfterEnd\":3,\"endOfMonth\":true,\"daysAfterEnd\":0}}," +
            "\"weekend\":[\"SATURDAY\",\"SUNDAY\"],\"holidays\":[]}}}"

    private fun stage(
        env: ProfileEnv,
        tenant: TenantId,
    ) {
        val context = env.context
        val handler =
            CalendarPackStagedHandler(
                context.getBean(CalendarPackReplica::class.java),
                ObjectMapper(),
                context.getBean(CalendarPackDecoder::class.java),
                effects = context.getBean(PackActivationRecompute::class.java),
            )
        env.inTenant(tenant) {
            env.inTx { handler.handle(testEnvelope(CalendarPackStagedHandler.STAGED, tenant, staged)) }
        }
    }

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
                    PeriodEnv(env).actAs(entity, "tax.approver")
                    stage(env, tenant)
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
    fun `INV-AC-16824 a request without packIdentifier is 400 over HTTP`(
        @TempDir dir: Path,
    ) {
        assertThat(get(dir, "?version=2026.2").statusCode()).isEqualTo(400)
    }

    @Test
    fun `INV-AC-16824 a request without version is 400 over HTTP`(
        @TempDir dir: Path,
    ) {
        assertThat(get(dir, "?packIdentifier=sg-calendar").statusCode()).isEqualTo(400)
    }

    @Test
    fun `INV-AC-16824 a request with a blank packIdentifier is 400 over HTTP`(
        @TempDir dir: Path,
    ) {
        assertThat(get(dir, "?packIdentifier=&version=2026.2").statusCode()).isEqualTo(400)
    }

    @Test
    fun `INV-AC-16824 control, the same request with packIdentifier and version present is 200`(
        @TempDir dir: Path,
    ) {
        val answer = get(dir, "?packIdentifier=sg-calendar&version=2026.2")
        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(200)
    }
}
