package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.gst.tenancy.syncedge.OffboardingPorts
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val CALLER = "identity:USR-004"
private const val SECOND = "identity:USR-0040"
private const val TENANT_ADMIN = "tenant.admin"
private const val COMMERCIAL_ADMIN = "commercial.admin"
private const val FINANCE_VIEWER = "finance.viewer"
private const val HOLDER = """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example"}"""

/**
 * offboardTenant over HTTP, real database (INV-AC-16048): the refusals of the override and the custody holder, the
 * response status and code and the stored record, compared by equality. The documents count is a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OffboardingAcknowledgementHttpTest {
    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()
    private lateinit var dir: Path
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var env: ProfileEnv

    private class Answer(
        val status: Int,
        val body: String,
    ) {
        val code: String? get() = Regex(""""code"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    }

    /** One stored record, the columns by name. */
    private class Row(
        private val columns: Map<String, String?>,
    ) {
        val scope get() = columns.getValue("scope")!!
        val state get() = columns.getValue("state")!!
        val holderName get() = columns.getValue("custody_holder_name")
        val holderEmail get() = columns.getValue("custody_holder_email")
        val holderMobile get() = columns.getValue("custody_holder_mobile")
        val exportContents get() =
            columns
                .getValue(
                    "export_contents",
                )!!
                .trim('{', '}')
                .split(',')
                .filter { it.isNotEmpty() }
        val overrideBy get() = columns.getValue("override_acknowledged_by")
        val overrideStatement get() = columns.getValue("override_statement")
        val handover get() = columns.getValue("handover_statement_ref")
        val confirmedBy get() = columns.getValue("second_confirmation_by")
    }

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("inv-t-22968-")
        context = serve(OffboardingPorts::class.java)
        env = ProfileEnv(context)
    }

    @AfterAll
    fun stop() {
        context.close()
        dir.toFile().deleteRecursively()
    }

    @BeforeEach
    fun reset() = OffboardingPorts.reset()

    private fun serve(vararg extra: Class<*>): ConfigurableApplicationContext =
        BoundaryFixture.startServlet(
            Files.createTempDirectory(dir, "ctx-"),
            listOf(EntityFactsIdentity::class.java, *extra),
            arrayOf(
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                "service-runtime.workload-identity.callers[1].service=control-plane-svc",
                "service-runtime.workload-identity.callers[1].subject=sa-control-plane-svc",
                "service-runtime.workload-identity.callers[2].service=billing-svc",
                "service-runtime.workload-identity.callers[2].subject=sa-billing-svc",
                "service-runtime.workload-identity.callers[3].service=control-plane-svc-two",
                "service-runtime.workload-identity.callers[3].subject=sa-control-plane-svc-two",
                "management.server.port=0",
            ),
            javaClass.classLoader,
        )

    private fun people(of: ProfileEnv = env) = of.context.getBean(TestPeople::class.java)

    /** A tenant with one entity; the caller is [CALLER] holding entity.admin and the second person tax.approver. */
    private fun scenario(
        callerRoles: Array<String> = arrayOf(ElectionRoles.ENTITY_ADMIN),
        block: (TenantId, EntityResponse) -> Unit,
    ) = env.withEntity { tenant, entity ->
        val p = people()
        p.caller = CALLER
        p.grant(CALLER, entity.entityRef, *callerRoles)
        p.grant(SECOND, entity.entityRef, ElectionRoles.TAX_APPROVER)
        EntityFactsIdentity.principal = EndUserPrincipal("user-a", tenant, setOf(EntityRef(entity.entityRef)))
        block(tenant, entity)
    }

    @Suppress("LongParameterList") // path, body, key, fixture, workload caller and whether an end-user token is sent
    private fun post(
        path: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
        of: ProfileEnv = env,
        workload: String = "console-bff",
        endUser: Boolean = true,
    ): Answer {
        val port = of.context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Workload-Token", "token-$workload")
                .apply { if (endUser) header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}") }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { Answer(it.statusCode(), it.body()) }
    }

    private fun offboard(
        tenant: TenantId,
        body: String,
        key: String = UUID.randomUUID().toString(),
        of: ProfileEnv = env,
    ) = post("/internal/v1/tenancy/tenants/${tenant.value}/offboarding", body, key, of)

    private fun entityBody(
        entity: EntityResponse,
        holder: String? = HOLDER,
        confirmer: String? = SECOND,
        extra: String = "",
    ): String {
        val parts =
            listOfNotNull(
                """"scope":"ENTITY"""",
                """"entityRef":"${entity.entityRef}"""",
                holder?.let { """"retentionCustodyHolder":$it""" },
                confirmer?.let { """"secondConfirmationBy":"$it"""" },
            ) + listOfNotNull(extra.ifEmpty { null })
        return "{" + parts.joinToString(",") + "}"
    }

    private fun tenantBody(extra: String = ""): String = """{"scope":"TENANT","retentionCustodyHolder":$HOLDER$extra}"""

    private fun rows(tenant: TenantId): List<Row> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select scope, state, custody_holder_name, custody_holder_email, custody_holder_mobile, " +
                        "export_contents::text as export_contents, override_acknowledged_by, override_statement, " +
                        "handover_statement_ref, second_confirmation_by " +
                        "from tenancy.offboarding_record where tenant_id = ? order by created_at, offboarding_ref",
                ).use { st ->
                    st.setObject(1, tenant.value)
                    st.executeQuery().use { rs ->
                        val names = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                        generateSequence {
                            if (rs.next()) {
                                Row(
                                    names.associateWith { rs.getString(it) },
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    /** The whole entity row as text, to show a refused call leaves the entity unchanged. */
    private fun entityRow(entity: EntityResponse): String =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select e::text from tenancy.entity e where e.ref = ?").use { st ->
                st.setString(1, entity.entityRef)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }.joinToString("|")
        }

    private fun json(text: String) = mapper.readTree(text)

    private fun refused(
        tenant: TenantId,
        answer: Answer,
        status: Int,
        code: String,
    ) {
        assertThat(answer.status).describedAs(answer.body).isEqualTo(status)
        assertThat(answer.code).describedAs(answer.body).isEqualTo(code)
        assertThat(rows(tenant)).isEmpty()
    }

    private fun overrideOf(
        by: String = CALLER,
        statement: String = "two drafts are abandoned",
    ) = """"unsubmittedOverride":{"acknowledgedBy":"$by","statement":"$statement"}"""

    @Test
    fun `INV-AC-16048 E1 with unsubmitted documents and no override is 409 TNC-008 with no record and the entity unchanged`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            val before = entityRow(entity)
            assertThat(before).isNotEmpty()
            refused(tenant, offboard(tenant, entityBody(entity)), 409, "TNC-008")
            assertThat(entityRow(entity)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-16048 E1 with an override is 202 and the record holds the caller user ID and the statement`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            val answer = offboard(tenant, entityBody(entity, extra = overrideOf()))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            val stored = rows(tenant).single()
            assertThat(stored.overrideBy).isEqualTo(CALLER)
            assertThat(stored.overrideStatement).isEqualTo("two drafts are abandoned")
        }

    @Test
    fun `INV-AC-16048 E2 without a holder is 400 SRT-001 and stores nothing`() =
        scenario { tenant, entity ->
            refused(tenant, offboard(tenant, entityBody(entity, holder = null)), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-16048 E2 with a holder and mobile stores and returns it unchanged`() =
        scenario { tenant, entity ->
            val holder =
                """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example","contactMobile":"+6591234567"}"""
            val answer = offboard(tenant, entityBody(entity, holder = holder))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            val stored = rows(tenant).single()
            assertThat(stored.holderName).isEqualTo("Ledger Custody Pte Ltd")
            assertThat(stored.holderEmail).isEqualTo("custody@ledger.example")
            assertThat(stored.holderMobile).isEqualTo("+6591234567")
            val returned = json(answer.body)["retentionCustodyHolder"]
            assertThat(returned["name"].asString()).isEqualTo("Ledger Custody Pte Ltd")
            assertThat(returned["contactEmail"].asString()).isEqualTo("custody@ledger.example")
            assertThat(returned["contactMobile"].asString()).isEqualTo("+6591234567")
        }

    @Test
    fun `INV-AC-16048 E2 with a holder and no mobile returns it without mobile`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().holderMobile).isNull()
            val returned = json(answer.body)["retentionCustodyHolder"]
            assertThat(returned["name"].asString()).isEqualTo("Ledger Custody Pte Ltd")
            assertThat(returned["contactMobile"]?.isNull ?: true).isTrue()
        }

    @Test
    fun `INV-AC-16048 an empty override statement is 400 SRT-001 and stores nothing`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            refused(tenant, offboard(tenant, entityBody(entity, extra = overrideOf(statement = ""))), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-16048 an empty holder contactEmail is 400 SRT-001 and stores nothing`() =
        scenario { tenant, entity ->
            val holder = """{"name":"Ledger","contactEmail":""}"""
            refused(tenant, offboard(tenant, entityBody(entity, holder = holder)), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-16048 an empty override acknowledgedBy is 400 SRT-001 and stores nothing`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            refused(tenant, offboard(tenant, entityBody(entity, extra = overrideOf(by = ""))), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-16048 an empty holder name is 400 SRT-001 and stores nothing`() =
        scenario { tenant, entity ->
            val holder = """{"name":"","contactEmail":"a@b.example"}"""
            refused(tenant, offboard(tenant, entityBody(entity, holder = holder)), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-16048 a holder contactEmail of one space is 409 TNC-009 and stores nothing`() =
        scenario { tenant, entity ->
            val holder = """{"name":"Ledger","contactEmail":" "}"""
            refused(tenant, offboard(tenant, entityBody(entity, holder = holder)), 409, "TNC-009")
        }
}
