package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.offboarding.OffboardOutcome
import com.dynokore.gst.tenancy.application.offboarding.OffboardRequest
import com.dynokore.gst.tenancy.application.offboarding.OffboardTenant
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
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
private const val FINANCE_VIEWER = "finance.viewer"
private const val HOLDER = """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example"}"""

/**
 * offboardTenant over HTTP through the filter chain of service-runtime (INV-T-16413): the response status and code of
 * the call and the stored offboarding record, compared by equality. The documents count and the export are stubs
 * (the owning services bind them in later waves, INV-Q-4900).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OffboardingHttpTest {
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

    private class Row(
        val ref: String,
        val scope: String,
        val entityRef: String?,
        val state: String,
        val holderName: String,
        val holderEmail: String,
        val holderMobile: String?,
        val exportState: String,
        val exportContents: List<String>,
        val overrideBy: String?,
        val overrideStatement: String?,
        val handover: String?,
        val requestedBy: String?,
        val confirmedBy: String?,
    )

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("inv-t-16413-")
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

    private fun post(
        path: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
        of: ProfileEnv = env,
    ): Answer {
        val port = of.context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
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
                    "select offboarding_ref, scope, entity_ref, state, custody_holder_name, custody_holder_email, " +
                        "custody_holder_mobile, export_state, export_contents::text, override_acknowledged_by, " +
                        "override_statement, handover_statement_ref, requested_by, second_confirmation_by " +
                        "from tenancy.offboarding_record where tenant_id = ? order by created_at, offboarding_ref",
                ).use { st ->
                    st.setObject(1, tenant.value)
                    st.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                Row(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                    rs.getString(5),
                                    rs.getString(6),
                                    rs.getString(7),
                                    rs.getString(8),
                                    rs
                                        .getString(9)
                                        .trim('{', '}')
                                        .split(',')
                                        .filter { it.isNotEmpty() },
                                    rs.getString(10),
                                    rs.getString(11),
                                    rs.getString(12),
                                    rs.getString(13),
                                    rs.getString(14),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun json(text: String) = mapper.readTree(text)

    @Test
    fun `INV-AC-2056 no custody holder is refused and a named one succeeds and is stored`() =
        scenario { tenant, entity ->
            val refused = offboard(tenant, entityBody(entity, holder = null))
            assertThat(refused.status).describedAs(refused.body).isEqualTo(400)
            assertThat(refused.code).describedAs(refused.body).isEqualTo("SRT-001")
            assertThat(rows(tenant)).isEmpty()
            val holder =
                """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example","contactMobile":"+6591234567"}"""
            val handover = """"handoverStatementRef":"HS-1""""
            val accepted = offboard(tenant, entityBody(entity, holder = holder, extra = handover))
            assertThat(accepted.status).describedAs(accepted.body).isEqualTo(202)
            val stored = rows(tenant).single()
            assertThat(stored.holderName).isEqualTo("Ledger Custody Pte Ltd")
            assertThat(stored.holderEmail).isEqualTo("custody@ledger.example")
            assertThat(stored.holderMobile).isEqualTo("+6591234567")
            assertThat(stored.handover).isEqualTo("HS-1")
            val returned = json(accepted.body)["retentionCustodyHolder"]
            assertThat(returned["name"].asString()).isEqualTo("Ledger Custody Pte Ltd")
            assertThat(returned["contactMobile"].asString()).isEqualTo("+6591234567")
            assertThat(json(accepted.body)["handoverStatementRef"].asString()).isEqualTo("HS-1")
        }

    @Test
    fun `probe a holder whose name is blank after trimming is 409 TNC-009 and nothing is stored`() =
        scenario { tenant, entity ->
            val blank = """{"name":"   ","contactEmail":"a@b.example"}"""
            val answer = offboard(tenant, entityBody(entity, holder = blank))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(409)
            assertThat(answer.code).isEqualTo("TNC-009")
            assertThat(rows(tenant)).isEmpty()
        }

    @Test
    fun `probe a holder whose contact email is blank after trimming is 409 TNC-009 and nothing is stored`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity, holder = """{"name":"Ledger","contactEmail":" "}"""))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(409)
            assertThat(answer.code).isEqualTo("TNC-009")
            assertThat(rows(tenant)).isEmpty()
        }

    @Test
    fun `probe a request with no retentionCustodyHolder object is 400 SRT-001 and not TNC-009`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity, holder = null))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(400)
            assertThat(answer.code).describedAs(answer.body).isEqualTo("SRT-001")
            assertThat(rows(tenant)).isEmpty()
        }

    @Test
    fun `probe an unsubmitted in-scope document and no override is 409 TNC-008 and nothing is recorded`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(409)
            assertThat(answer.code).isEqualTo("TNC-008")
            assertThat(rows(tenant)).isEmpty()
        }

    @Test
    fun `probe an unsubmitted in-scope document with an override succeeds and the override is stored`() =
        scenario { tenant, entity ->
            OffboardingPorts.unsubmitted = 2
            val override =
                """"unsubmittedOverride":{"acknowledgedBy":"$CALLER","statement":"two drafts are abandoned"}"""
            val answer = offboard(tenant, entityBody(entity, extra = override))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            val stored = rows(tenant).single()
            assertThat(stored.overrideBy).isEqualTo(CALLER)
            assertThat(stored.overrideStatement).isEqualTo("two drafts are abandoned")
        }

    private fun existingRecordBlocks(state: ExportState) =
        scenario { tenant, entity ->
            OffboardingPorts.state = state
            val first = offboard(tenant, entityBody(entity))
            assertThat(first.status).describedAs(first.body).isEqualTo(202)
            val again = offboard(tenant, entityBody(entity))
            assertThat(again.status).describedAs(again.body).isEqualTo(409)
            assertThat(again.code).isEqualTo("TNC-010")
            assertThat(rows(tenant)).hasSize(1)
        }

    @Test
    fun `probe a COMPLETED record of the same scope and entity with a new key is 409 TNC-010 and creates nothing`() =
        existingRecordBlocks(ExportState.READY)

    @Test
    fun `probe a REQUESTED record of the same scope and entity with a new key is 409 TNC-010 and creates nothing`() =
        existingRecordBlocks(ExportState.REQUESTED)

    @Test
    fun `probe the same Idempotency-Key replayed returns the original record and stores one`() =
        scenario { tenant, entity ->
            val key = UUID.randomUUID().toString()
            val first = offboard(tenant, entityBody(entity), key)
            val replay = offboard(tenant, entityBody(entity), key)
            assertThat(first.status).describedAs(first.body).isEqualTo(202)
            assertThat(replay.status).describedAs(replay.body).isEqualTo(202)
            val originalRef = json(first.body)["offboardingRef"].asString()
            assertThat(json(replay.body)["offboardingRef"].asString()).isEqualTo(originalRef)
            assertThat(rows(tenant)).hasSize(1)
        }

    @Test
    fun `probe an export READY with all six contents members stores the record COMPLETED`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().state).isEqualTo("COMPLETED")
            assertThat(json(answer.body)["state"].asString()).isEqualTo("COMPLETED")
            assertThat(rows(tenant).single().exportContents)
                .containsExactlyInAnyOrderElementsOf(ExportContent.entries.map { it.name })
        }

    @Test
    fun `probe an export READY with five contents members is not COMPLETED`() =
        scenario { tenant, entity ->
            OffboardingPorts.contents = ExportContent.entries.dropLast(1)
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().state).isNotEqualTo("COMPLETED")
            assertThat(json(answer.body)["state"].asString()).isNotEqualTo("COMPLETED")
        }

    @Test
    fun `probe an export manifest FAILED leaves the entity open and a new key is a new request`() =
        scenario { tenant, entity ->
            OffboardingPorts.state = ExportState.FAILED
            val failed = offboard(tenant, entityBody(entity))
            assertThat(failed.status).describedAs(failed.body).isEqualTo(202)
            assertThat(rows(tenant).single().state).isEqualTo("FAILED")
            OffboardingPorts.state = ExportState.READY
            val retry = offboard(tenant, entityBody(entity))
            assertThat(retry.status).describedAs(retry.body).isEqualTo(202)
            assertThat(rows(tenant).map { it.state }).containsExactly("FAILED", "COMPLETED")
        }

    @Test
    fun `probe a handoverStatementRef of 200 characters is stored and returned equal`() =
        scenario { tenant, entity ->
            val ref = "h".repeat(200)
            val answer = offboard(tenant, entityBody(entity, extra = """"handoverStatementRef":"$ref""""))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().handover).isEqualTo(ref)
            assertThat(json(answer.body)["handoverStatementRef"].asString()).isEqualTo(ref)
        }

    @Test
    fun `probe a handoverStatementRef absent is stored and returned absent`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().handover).isNull()
            assertThat(json(answer.body).has("handoverStatementRef")).isFalse()
        }

    @Test
    fun `probe a contactMobile absent is stored and returned absent`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(rows(tenant).single().holderMobile).isNull()
            assertThat(json(answer.body)["retentionCustodyHolder"].has("contactMobile")).isFalse()
        }

    private fun confirmation(
        confirmer: String,
        roles: Array<String>,
        expected: Int,
    ) = scenario { tenant, entity ->
        people().grant(confirmer, entity.entityRef, *roles)
        val answer = offboard(tenant, entityBody(entity, confirmer = confirmer))
        assertThat(answer.status).describedAs(answer.body).isEqualTo(expected)
        assertThat(rows(tenant)).hasSize(if (expected == 202) 1 else 0)
    }

    @Test
    fun `probe offboarding with scope ENTITY and secondConfirmationBy equal to the caller is 403 and stores nothing`() =
        confirmation(CALLER, arrayOf(ElectionRoles.ENTITY_ADMIN), 403)

    @Test
    fun `probe scope ENTITY with the confirmer USR-0040 against the caller USR-004 is accepted`() =
        confirmation(SECOND, arrayOf(ElectionRoles.TAX_APPROVER), 202)

    @Test
    fun `probe offboarding with scope ENTITY and a confirmer holding only finance viewer is 403 and stores nothing`() =
        confirmation("identity:USR-0041", arrayOf(FINANCE_VIEWER), 403)

    @Test
    fun `probe offboarding with scope ENTITY and a confirmer holding only tax approver is accepted`() =
        confirmation("identity:USR-0042", arrayOf(ElectionRoles.TAX_APPROVER), 202)

    private fun useCaseTenantScope(
        confirmer: String?,
        callerRoles: Array<String>,
    ) = scenario(callerRoles) { tenant, _ ->
        val outcome =
            context
                .getBean(OffboardTenant::class.java)
                .forWholeTenant(
                    tenant.value.toString(),
                    UUID.randomUUID().toString(),
                    OffboardRequest(
                        OffboardingScope.TENANT,
                        null,
                        RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example"),
                        secondConfirmationBy = confirmer,
                    ),
                )
        assertThat(outcome).isInstanceOf(OffboardOutcome.Accepted::class.java)
        val stored = rows(tenant).single()
        assertThat(stored.scope).isEqualTo("TENANT")
        assertThat(stored.state).isEqualTo("COMPLETED")
        assertThat(stored.confirmedBy).isNull()
    }

    @Test
    fun `INV-AC-8104 the use case with scope TENANT and no secondConfirmationBy completes and stores a record`() =
        useCaseTenantScope(null, arrayOf(TENANT_ADMIN))

    @Test
    fun `INV-AC-8104 the use case with scope TENANT and a confirmer equal to the caller gives the same outcome`() =
        useCaseTenantScope(CALLER, arrayOf(TENANT_ADMIN))

    @Test
    fun `INV-AC-8104 the use case with scope TENANT for a finance viewer gives the same outcome`() =
        useCaseTenantScope(null, arrayOf(FINANCE_VIEWER))

    private fun httpTenantScopeRefused(
        caller: String,
        roles: Array<String>,
    ) = scenario { tenant, entity ->
        people().caller = caller
        people().grant(caller, entity.entityRef, *roles)
        val answer = offboard(tenant, tenantBody())
        assertThat(answer.status).describedAs(answer.body).isEqualTo(403)
        assertThat(rows(tenant)).isEmpty()
    }

    @Test
    fun `INV-AC-8104 the HTTP operation with scope TENANT answers 403 for tenant admin and stores nothing`() =
        httpTenantScopeRefused("identity:USR-T1", arrayOf(TENANT_ADMIN))

    @Test
    fun `INV-AC-8104 the HTTP operation with scope TENANT answers 403 for entity admin and stores nothing`() =
        httpTenantScopeRefused("identity:USR-T2", arrayOf(ElectionRoles.ENTITY_ADMIN))

    @Test
    fun `INV-AC-8104 the HTTP operation with scope TENANT answers 403 for a caller holding only finance viewer`() =
        httpTenantScopeRefused("identity:USR-T3", arrayOf(FINANCE_VIEWER))

    @Test
    fun `INV-AC-8104 the HTTP operation with scope TENANT answers 403 for the control-plane-svc principal`() =
        httpTenantScopeRefused("service:control-plane-svc", emptyArray())

    @Test
    fun `INV-AC-8104 scope ENTITY with an entityRef and no secondConfirmationBy is 400 and stores nothing`() =
        scenario { tenant, entity ->
            val answer = offboard(tenant, entityBody(entity, confirmer = null))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(400)
            assertThat(rows(tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-8104 goLive without secondConfirmationBy is refused with 4xx`() =
        scenario { _, entity ->
            val answer = post("/internal/v1/tenancy/entities/${entity.entityRef}/go-live", "{}")
            assertThat(answer.status).describedAs(answer.body).isBetween(400, 499)
        }

    @Test
    fun `INV-AC-8177 scope TENANT is refused 403 for three persons and scope ENTITY still succeeds`() =
        scenario { tenant, entity ->
            val persons =
                mapOf(
                    "identity:USR-P1" to TENANT_ADMIN,
                    "identity:USR-P2" to ElectionRoles.ENTITY_ADMIN,
                    "identity:USR-P3" to FINANCE_VIEWER,
                )
            persons.forEach { (person, role) ->
                people().caller = person
                people().grant(person, entity.entityRef, role)
                val answer = offboard(tenant, tenantBody())
                assertThat(answer.status).describedAs("$person: ${answer.body}").isEqualTo(403)
            }
            assertThat(rows(tenant)).isEmpty()
            people().caller = CALLER
            val entityScope = offboard(tenant, entityBody(entity))
            assertThat(entityScope.status).describedAs(entityScope.body).isEqualTo(202)
            assertThat(rows(tenant).map { it.scope }).containsExactly("ENTITY")
        }
}
