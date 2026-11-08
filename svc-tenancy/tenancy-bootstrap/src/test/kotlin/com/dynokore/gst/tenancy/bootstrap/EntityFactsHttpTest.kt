package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.questionnaire.QuestionnairePermissions
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * answerQuestionnaire with entity facts over HTTP through the filter chain of service-runtime with the generated
 * entity-reference resource of the contract (INV-T-9770; INV-AC-9769). The service writes no entity check of its own:
 * an `entityRef` outside the caller's entity set is refused by the filter before the handler runs.
 */
class EntityFactsHttpTest {
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

    private fun ProfileEnv.post(
        tenant: TenantId,
        body: String,
    ): HttpResponse<String> {
        allowQuestionnaire(tenant)
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(
                    URI.create(
                        "http://localhost:$port/internal/v1/tenancy/tenants/${tenant.value}/questionnaire-answers",
                    ),
                ).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun json(
        entityRef: String?,
        facts: String?,
    ): String {
        val entity = entityRef?.let { ""","entityRef":"$it"""" }.orEmpty()
        val values = facts?.let { ""","entityFacts":$it""" }.orEmpty()
        return """{"questionnaireVersion":"2026.1","answers":{"OQ-1":"one"}$entity$values}"""
    }

    private fun rows(): Long =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from tenancy.entity_fact").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun granted(
        tenant: TenantId,
        vararg entities: String,
    ) {
        EntityFactsIdentity.principal = EndUserPrincipal("user-a", tenant, entities.map { EntityRef(it) }.toSet())
    }

    @Test
    fun `INV-AC-9769 an entity outside the caller entity set is 403 SRT-004 from the filter and stores nothing`(
        @TempDir dir: Path,
    ) {
        serve(dir) { env ->
            val foreign = env.withEntity { _, entity -> entity }
            env.withEntity { tenant, own ->
                run {
                    val second = env.entity(tenant, env.group(tenant))
                    granted(tenant, own.entityRef)
                    val before = rows()
                    val ok = env.post(tenant, json(own.entityRef, """{"recordsPurchases":true}"""))
                    assertThat(ok.statusCode()).describedAs(ok.body()).isEqualTo(201)
                    assertThat(rows()).isEqualTo(before + 1)
                    val afterOk = rows()
                    // the entity of another tenant, and an entity of this tenant the caller is not granted
                    listOf(foreign.entityRef, second.entityRef).forEach {
                        val refused = env.post(tenant, json(it, """{"recordsPurchases":true}"""))
                        assertThat(refused.statusCode()).describedAs(refused.body()).isEqualTo(403)
                        assertThat(refused.body()).contains("SRT-004")
                    }
                    assertThat(rows()).isEqualTo(afterOk)
                }
            }
        }
    }

    @Test
    fun `INV-AC-9769 entityFacts without entityRef is 400 SRT-001 over HTTP and stores nothing`(
        @TempDir dir: Path,
    ) {
        serve(dir) { env ->
            env.withEntity { tenant, own ->
                granted(tenant, own.entityRef)
                val before = rows()
                val answer = env.post(tenant, json(null, """{"recordsPurchases":true}"""))
                assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(400)
                assertThat(answer.body()).contains("SRT-001")
                assertThat(rows()).isEqualTo(before)
            }
        }
    }

    private fun refusedFacts(
        dir: Path,
        facts: String,
    ) {
        serve(dir) { env ->
            env.withEntity { tenant, own ->
                granted(tenant, own.entityRef)
                val before = rows()
                val answer = env.post(tenant, json(own.entityRef, facts))
                assertThat(answer.statusCode()).describedAs("$facts: ${answer.body()}").isEqualTo(400)
                assertThat(rows()).isEqualTo(before)
            }
        }
    }

    @Test
    fun `INV-AC-9769 a fact that is the string false is 400 and stores nothing`(
        @TempDir dir: Path,
    ) = refusedFacts(dir, """{"recordsPurchases":"false"}""")

    @Test
    fun `INV-AC-9769 an entityFacts object with the unknown name recordsSales is 400 and stores nothing`(
        @TempDir dir: Path,
    ) = refusedFacts(dir, """{"recordsPurchases":true,"recordsSales":true}""")

    @Test
    fun `INV-AC-9769 the contract resource lists answerQuestionnaire and platformBuild is exactly 0_23_4`() {
        val lines =
            javaClass.classLoader
                .getResources("META-INF/gst/entity-ref-operations.tsv")
                .toList()
                .flatMap { it.readText().lines() }
        assertThat(lines).contains("POST\t/internal/v1/tenancy/tenants/{tenantRef}/questionnaire-answers\tentityRef")
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        val pin = Regex("""(?m)^platformBuild\s*=\s*"([^"]*)"""").find(toml)!!.groupValues[1]
        assertThat(pin).isEqualTo("0.23.4")
    }
}
