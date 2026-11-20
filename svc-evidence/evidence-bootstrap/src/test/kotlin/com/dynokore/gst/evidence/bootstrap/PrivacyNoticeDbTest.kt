package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.application.cases.NoticePrompter
import com.dynokore.gst.evidence.application.cases.OpenPrivacyCaseCommand
import com.dynokore.gst.evidence.application.cases.OpenResult
import com.dynokore.gst.evidence.application.cases.PrivacyCaseService
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The notice step and the deadline of a case on PostgreSQL, in the service as TPL-18 composes it (INV-T-6842;
 * INV-REQ-1403 behavior 3, INV-REQ-1401). The step runs for one tenant per call. The dates of the tenant-lead case are
 * counted from today, since a policy value is effective from the day it is written and never backdated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivacyNoticeDbTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService
    private lateinit var cases: PrivacyCaseService
    private lateinit var prompter: NoticePrompter
    private lateinit var scope: TenantScope
    private lateinit var runner: TransactionRunner

    @BeforeAll
    fun start() {
        System.setProperty(PrivacyRoleMapBeans.PROPERTY, "true")
        dir = Files.createTempDirectory("pnd")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, PrivacyRoleMapBeans::class.java))
        cases = service.bean(PrivacyCaseService::class.java)
        prompter = service.bean(NoticePrompter::class.java)
        scope = service.bean(TenantScope::class.java)
        runner = service.bean(TransactionRunner::class.java)
    }

    @AfterAll
    fun stop() {
        service.close()
        System.clearProperty(PrivacyRoleMapBeans.PROPERTY)
        dir.toFile().deleteRecursively()
    }

    private fun tenant(): UUID {
        val tenant = UUID.randomUUID()
        listOf(HANDLER to "privacy.handler", POLICY to "privacy.policy").forEach { (user, role) ->
            service.grants.grant(
                TenantId(tenant),
                user,
                service.grants.role(role, GrantScope.TENANT, tenant.toString()),
            )
        }
        return tenant
    }

    private fun <T> bound(
        tenant: UUID,
        block: () -> T,
    ): T = scope.bind(TenantId(tenant), "svc:evidence-svc") { runner.inTransaction { block() } }

    private fun open(
        tenant: UUID,
        receivedOn: LocalDate,
        reference: String = "REQ-1",
    ): UUID {
        val result =
            bound(tenant) {
                cases.open(
                    TenantId(tenant),
                    Principal(HANDLER, PrincipalKind.HUMAN),
                    OpenPrivacyCaseCommand("SUBJ-1", RequestKind.ACCESS, receivedOn, reference),
                    RetentionService.today(),
                )
            }
        return (result as OpenResult.Opened).record.caseId
    }

    private fun prompt(
        tenant: UUID,
        on: String,
    ): Int = bound(tenant) { prompter.promptNotices(TenantId(tenant), LocalDate.parse(on)) }

    private fun prompt(
        tenant: UUID,
        on: LocalDate,
    ): Int = prompt(tenant, on.toString())

    private fun scalar(
        sql: String,
        vararg args: Any,
    ): String? =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    private fun promptedAt(caseId: UUID): String? =
        scalar("select notice_prompted_at::text from evidence.case_register where case_id = ?", caseId)

    private fun events(caseId: UUID): List<JsonNode> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select type, data::text from ${EvidenceDb.SCHEMA}.outbox where subject = ? order by 1",
                ).use { st ->
                    st.setString(1, caseId.toString())
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                            .map { (type, data) ->
                                mapper.createObjectNode().also {
                                    it.put("type", type)
                                    it.set("data", mapper.readTree(data))
                                }
                            }.toList()
                    }
                }
        }

    private fun setLead(
        tenant: UUID,
        days: Int,
    ): Int {
        val node = mapper.createObjectNode()
        node.put("scopeLevel", "TENANT")
        node.put("scopeRef", tenant.toString())
        val selected = node.putArray("selected").addObject()
        selected.put("option", "PACK_DEFAULT_30D")
        selected.putObject("value").put("noticeLeadDays", days)
        node.put("effectiveDate", RetentionService.today().toString())
        val port = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/policies/VP-63"))
                .header("Authorization", "Bearer ${TestIdentity.token(POLICY, tenant)}")
                .header("X-Workload-Token", "token-console-bff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(node)))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    @Test
    fun `INV-AC-10588 the notice is prompted once from 2026-03-24 with one PrivacyNoticeDue row of the five properties`() {
        val t = tenant()
        val id = open(t, LocalDate.parse("2026-03-01"))
        scalar("select response_deadline::text from evidence.case_register where case_id = ?", id) shouldBe "2026-03-31"
        prompt(t, "2026-03-23") shouldBe 0
        promptedAt(id) shouldBe null
        events(id).size shouldBe 0
        prompt(t, "2026-03-24") shouldBe 1
        val first = promptedAt(id)
        (first != null) shouldBe true
        val rows = events(id)
        rows.size shouldBe 1
        rows[0].get("type").asString() shouldBe "com.dynokore.gst.platform.PrivacyNoticeDue.v1"
        val data = rows[0].get("data")
        data.propertyNames().toSet() shouldBe setOf("caseId", "requestKind", "receivedOn", "responseDeadline", "noticeLeadDays")
        data.get("caseId").asString() shouldBe id.toString()
        data.get("requestKind").asString() shouldBe "ACCESS"
        LocalDate.parse(data.get("receivedOn").asString()) shouldBe LocalDate.parse("2026-03-01")
        LocalDate.parse(data.get("responseDeadline").asString()) shouldBe LocalDate.parse("2026-03-31")
        data.get("noticeLeadDays").asInt() shouldBe NOTICE_LEAD
        prompt(t, "2026-03-25") shouldBe 0
        events(id).size shouldBe 1
        promptedAt(id) shouldBe first
        scalar("select response_deadline::text from evidence.case_register where case_id = ?", id) shouldBe "2026-03-31"
    }

    @Test
    fun `INV-AC-1404 a closed case is not prompted and writes no row`() {
        val t = tenant()
        val id = open(t, LocalDate.parse("2026-03-01"))
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "update evidence.case_register set status = 'CLOSED', closed_at = now()," +
                        " closure_outcome = 'ANSWERED', closure_basis = 'done' where case_id = ?",
                ).use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
        }
        prompt(t, "2026-03-30") shouldBe 0
        events(id).size shouldBe 0
        promptedAt(id) shouldBe null
    }

    @Test
    fun `INV-AC-10587 a tenant lead of 14 prompts from fourteen days before the deadline and not a day earlier`() {
        val t = tenant()
        setLead(t, LEAD_LONGER) shouldBe 200
        val received = RetentionService.today()
        val id = open(t, received)
        val deadline = received.plusDays(PACK_DAYS.toLong())
        prompt(t, deadline.minusDays(LEAD_LONGER + 1L)) shouldBe 0
        promptedAt(id) shouldBe null
        prompt(t, deadline.minusDays(LEAD_LONGER.toLong())) shouldBe 1
        events(id).single().get("data").get("noticeLeadDays").asInt() shouldBe LEAD_LONGER
    }

    @Test
    fun `INV-AC-10587 a tenant lead of 5 is refused at setPolicy and the lead stays 7`() {
        val t = tenant()
        setLead(t, LEAD_BELOW_MIN) shouldBe 422
        val id = open(t, LocalDate.parse("2026-03-01"))
        prompt(t, "2026-03-23") shouldBe 0
        prompt(t, "2026-03-24") shouldBe 1
        events(id).single().get("data").get("noticeLeadDays").asInt() shouldBe NOTICE_LEAD
    }

    private companion object {
        const val HANDLER = "u-handler"
        const val POLICY = "u-policy"
        const val NOTICE_LEAD = 7
        const val PACK_DAYS = 30
        const val LEAD_LONGER = 14
        const val LEAD_BELOW_MIN = 5
    }
}
