package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * One role per permission key, so that a principal holds one key and not another. Active only while a test class sets
 * [PROPERTY]; the roles are test names and no service code compares them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(PrivacyRoleMapBeans.PROPERTY, havingValue = "true")
class PrivacyRoleMapBeans {
    @Bean
    @Primary
    fun privacyRoleMap(): OperationRoleMap =
        OperationRoleMap {
            val role = ROLES[it]
            if (role == null) emptyMap() else mapOf(role to CellAllowance.PERMITTED)
        }

    companion object {
        const val PROPERTY = "evidence.test.privacy-role-map"
        const val HANDLE = "privacy-request.handle"
        const val READ = "privacy-case.read"
        const val POLICY = "vp-63.set"
        const val SPACE = "privacy-request.handle "
        const val VIEW = "privacy-request.view"
        const val SECURITY = "security-case.record"
        val ROLES =
            mapOf(
                HANDLE to "privacy.handler",
                READ to "privacy.reader",
                POLICY to "privacy.policy",
                SPACE to "lookalike.space",
                VIEW to "lookalike.view",
                SECURITY to "lookalike.record",
            )
    }
}

/**
 * `openPrivacyCase` and `closePrivacyCase` over HTTP in the service as TPL-18 composes it, on PostgreSQL (INV-T-6842;
 * INV-REQ-1403). The permission of each principal is the TENANT-scope grant fed to the service's one
 * `AuthorityReplica`. The tenant-period cases of INV-AC-10587 wait for VP-63 to be available (see the MR).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass", "TooManyFunctions") // one HTTP test class for the two operations of one task
class PrivacyEndpointsTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService

    @BeforeAll
    fun start() {
        System.setProperty(PrivacyRoleMapBeans.PROPERTY, "true")
        dir = Files.createTempDirectory("pet")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, PrivacyRoleMapBeans::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
        System.clearProperty(PrivacyRoleMapBeans.PROPERTY)
        dir.toFile().deleteRecursively()
    }

    private val port: String
        get() = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")!!

    private class Reply(
        val status: Int,
        val body: JsonNode,
    ) {
        val code: String? get() = body.get("code")?.asString()
    }

    @Suppress("LongParameterList") // one request helper: method, path, caller, tenant, body and key
    private fun send(
        method: String,
        path: String,
        user: String,
        tenant: UUID,
        body: String? = null,
        key: String? = null,
    ): Reply {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer ${TestIdentity.token(user, tenant)}")
                .header("X-Workload-Token", "token-console-bff")
                .header("Content-Type", "application/json")
        key?.let { builder.header("Idempotency-Key", it) }
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), mapper.readTree(response.body()))
    }

    private fun openBody(
        reference: String = "REQ-1",
        receivedOn: String = RECEIVED,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val node = mapper.createObjectNode()
        node.put("subjectRef", "SUBJ-1")
        node.put("requestKind", "ACCESS")
        node.put("receivedOn", receivedOn)
        node.put("requestReference", reference)
        extra.forEach { (name, value) -> node.put(name, value) }
        return mapper.writeValueAsString(node)
    }

    private fun open(
        user: String,
        tenant: UUID,
        body: String = openBody(),
        key: String? = uuid(),
    ) = send("POST", CASES_PATH, user, tenant, body, key)

    private fun close(
        user: String,
        tenant: UUID,
        caseId: String,
        outcome: String = "ANSWERED",
        key: String? = uuid(),
    ): Reply {
        val node = mapper.createObjectNode()
        node.put("outcome", outcome)
        node.put("basis", "Sent as requested.")
        return send("POST", "$CASES_PATH/$caseId/closure", user, tenant, mapper.writeValueAsString(node), key)
    }

    private fun get(
        user: String,
        tenant: UUID,
        caseId: String,
    ) = send("GET", "$CASES_PATH/$caseId", user, tenant)

    /** A tenant whose principals hold the given roles at TENANT scope: `principal to role`. */
    private fun tenant(vararg grants: Pair<String, String>): UUID {
        val tenant = UUID.randomUUID()
        grants.forEach { (principal, role) ->
            service.grants.grant(
                TenantId(tenant),
                principal,
                service.grants.role(role, GrantScope.TENANT, tenant.toString()),
            )
        }
        return tenant
    }

    private fun handlerTenant(): UUID =
        tenant(HANDLER to "privacy.handler", READER to "privacy.reader", POLICY to "privacy.policy")

    /** A VP-63 `setPolicy` of [tenant] by the policy principal: option [option] with the given value members. */
    private fun setPolicy(
        tenant: UUID,
        option: String,
        value: Map<String, Int>,
    ): Reply {
        val node = mapper.createObjectNode()
        node.put("scopeLevel", "TENANT")
        node.put("scopeRef", tenant.toString())
        val selected = node.putArray("selected").addObject()
        selected.put("option", option)
        val values = selected.putObject("value")
        // The row VP-63 holds a record of both members; the member a case does not set keeps its default.
        values.put("responseDays", PACK_DAYS)
        values.put("noticeLeadDays", NOTICE_LEAD_DEFAULT)
        value.forEach { (name, days) -> values.put(name, days) }
        node.put("effectiveDate", RetentionService.today().toString())
        node.put("reason", "Tenant privacy request period or notice lead")
        return send("POST", "/internal/v1/policies/VP-63", POLICY, tenant, mapper.writeValueAsString(node), uuid())
    }

    private fun uuid() = UUID.randomUUID().toString()

    private fun rows(tenant: UUID): Int =
        scalar("select count(*) from evidence.case_register where tenant_id = ?", tenant).toInt()

    private fun scalar(
        sql: String,
        vararg args: Any,
    ): String =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun stored(
        tenant: UUID,
        column: String,
    ): String = scalar("select $column::text from evidence.case_register where tenant_id = ?", tenant)

    /** A SECURITY row as `recordSecurityCase` stores it, inserted directly: it is the other kind of case. */
    private fun insertSecurityCase(tenant: UUID): UUID {
        val id = UUID.randomUUID()
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into evidence.case_register (tenant_id, case_id, case_kind, status, opened_at," +
                        " review_reference, audit_log_entry_ids, outcome, breach_assessment_opened)" +
                        " values (?, ?, 'SECURITY', 'OPEN', now(), 'R-1', array[gen_random_uuid()], 'ok', false)",
                ).use {
                    it.setObject(1, tenant)
                    it.setObject(2, id)
                    it.executeUpdate()
                }
        }
        return id
    }

    @Test
    fun `INV-AC-1404 a request received on 1 March with no tenant period is due 31 March with period 30`() {
        val t = handlerTenant()
        val answer = open(HANDLER, t)
        answer.status shouldBe 201
        answer.body.get("responseDeadline").asString() shouldBe "2026-03-31"
        answer.body.get("deadlinePeriodDays").asInt() shouldBe PACK_DAYS
        answer.body.get("requestKind").asString() shouldBe "ACCESS"
        answer.body.get("requestReference").asString() shouldBe "REQ-1"
        answer.body.get("receivedOn").asString() shouldBe RECEIVED
        answer.body.has("noticePromptedAt") shouldBe false
        stored(t, "response_deadline") shouldBe "2026-03-31"
        stored(t, "deadline_period_days") shouldBe "30"
    }

    @Test
    fun `INV-AC-10587 a tenant period of 14 gives 2026-03-15 and 45 is refused POL-013 and never reaches the case`() {
        val t = handlerTenant()
        val refused = setPolicy(t, "SHORTER", mapOf("responseDays" to LONGER_THAN_PACK))
        refused.status shouldBe 422
        refused.code shouldBe "POL-013"
        open(HANDLER, t, openBody("REQ-A")).body.get("responseDeadline").asString() shouldBe "2026-03-31"
        setPolicy(t, "SHORTER", mapOf("responseDays" to SHORTER)).status shouldBe 200
        val answer = open(HANDLER, t, openBody("REQ-B"))
        answer.status shouldBe 201
        answer.body.get("responseDeadline").asString() shouldBe "2026-03-15"
        answer.body.get("deadlinePeriodDays").asInt() shouldBe SHORTER
        scalar(
            "select response_deadline::text from evidence.case_register where tenant_id = ? and request_reference = ?",
            t,
            "REQ-B",
        ) shouldBe "2026-03-15"
    }

    @Test
    fun `INV-AC-10587 a lead of 5 is refused POL-013 and a lead of 14 is stored`() {
        val t = handlerTenant()
        val refused = setPolicy(t, "SHORTER", mapOf("noticeLeadDays" to LEAD_BELOW_MIN))
        refused.status shouldBe 422
        refused.code shouldBe "POL-013"
        setPolicy(t, "SHORTER", mapOf("noticeLeadDays" to LEAD_LONGER)).status shouldBe 200
    }

    @Test
    fun `INV-AC-10587 the stored deadline of an existing case does not change when the period is changed later`() {
        val t = handlerTenant()
        val opened = open(HANDLER, t)
        setPolicy(t, "SHORTER", mapOf("responseDays" to SHORTER)).status shouldBe 200
        stored(t, "response_deadline") shouldBe "2026-03-31"
        val read = get(READER, t, opened.body.get("caseId").asString())
        read.status shouldBe 200
        read.body.get("responseDeadline").asString() shouldBe "2026-03-31"
        read.body.get("deadlinePeriodDays").asInt() shouldBe PACK_DAYS
    }

    @Test
    fun `INV-AC-1404 getCase of an opened case carries the five values and no noticePromptedAt`() {
        val t = handlerTenant()
        val opened = open(HANDLER, t).body.get("caseId").asString()
        val read = get(READER, t, opened).body
        listOf("requestKind", "requestReference", "receivedOn", "responseDeadline", "deadlinePeriodDays").forEach {
            withClue(it) { read.has(it) shouldBe true }
        }
        read.has("noticePromptedAt") shouldBe false
        read.get("status").asString() shouldBe "OPEN"
    }

    @Test
    fun `INV-AC-10588 the repeat of a requestReference answers 200 with the original case and one row`() {
        val t = handlerTenant()
        val first = open(HANDLER, t)
        val again = open(HANDLER, t)
        again.status shouldBe 200
        again.body.get("caseId").asString() shouldBe first.body.get("caseId").asString()
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-10588 the same requestReference with another receivedOn answers 409 and stores nothing more`() {
        val t = handlerTenant()
        open(HANDLER, t).status shouldBe 201
        val conflict = open(HANDLER, t, openBody(receivedOn = "2026-03-02"))
        conflict.status shouldBe 409
        rows(t) shouldBe 1
        stored(t, "received_on") shouldBe RECEIVED
    }

    @Test
    fun `INV-AC-10588 a receivedOn in the future answers 422 and stores nothing`() {
        val t = handlerTenant()
        open(HANDLER, t, openBody(receivedOn = "2999-01-01")).status shouldBe 422
        rows(t) shouldBe 0
    }

    @Test
    fun `INV-AC-10588 a request body with responseDeadline is refused by the schema and stores nothing`() {
        val t = handlerTenant()
        val answer = open(HANDLER, t, openBody(extra = mapOf("responseDeadline" to "2099-01-01")))
        answer.status shouldBe 400
        answer.code shouldBe "SRT-001"
        rows(t) shouldBe 0
    }

    @Test
    fun `INV-AC-10588 the same key and body replays the stored answer and a different body answers 422 SRT-009`() {
        val t = handlerTenant()
        val key = uuid()
        val first = open(HANDLER, t, key = key)
        val replay = open(HANDLER, t, key = key)
        first.status shouldBe 201
        replay.status shouldBe 200
        replay.body.get("caseId").asString() shouldBe first.body.get("caseId").asString()
        val other = open(HANDLER, t, openBody("REQ-OTHER"), key)
        other.status shouldBe 422
        other.code shouldBe "SRT-009"
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-10588 a missing or malformed Idempotency-Key answers 400 SRT-002 on both operations`() {
        val t = handlerTenant()
        val opened = open(HANDLER, t).body.get("caseId").asString()
        listOf(null, "not-a-uuid").forEach {
            val a = open(HANDLER, t, openBody("REQ-K"), it)
            val b = close(HANDLER, t, opened, key = it)
            withClue("key $it") {
                a.code shouldBe "SRT-002"
                b.code shouldBe "SRT-002"
            }
        }
        rows(t) shouldBe 1
        stored(t, "status") shouldBe "OPEN"
    }

    @Test
    fun `INV-AC-10588 closing a case answers 200 and the repeat answers 200 and another outcome answers 409`() {
        val t = handlerTenant()
        val id = open(HANDLER, t).body.get("caseId").asString()
        val closed = close(HANDLER, t, id)
        closed.status shouldBe 200
        closed.body.get("status").asString() shouldBe "CLOSED"
        closed.body.get("outcome").asString() shouldBe "ANSWERED"
        closed.body.get("responseDeadline").asString() shouldBe "2026-03-31"
        close(HANDLER, t, id).status shouldBe 200
        close(HANDLER, t, id, outcome = "REFUSED").status shouldBe 409
        stored(t, "closure_outcome") shouldBe "ANSWERED"
    }

    @Test
    fun `INV-AC-10588 closing an unknown case answers 404 and a security case answers 409`() {
        val t = tenant(HANDLER to "privacy.handler")
        close(HANDLER, t, UUID.randomUUID().toString()).status shouldBe 404
        close(HANDLER, t, insertSecurityCase(t).toString()).status shouldBe 409
    }

    @Test
    fun `INV-AC-10588 a case recorded as a security case has none of the five values through getCase`() {
        val t = handlerTenant()
        val body = get(READER, t, insertSecurityCase(t).toString()).body
        listOf("requestKind", "requestReference", "receivedOn", "responseDeadline", "deadlinePeriodDays").forEach {
            withClue(it) { body.has(it) shouldBe false }
        }
        body.get("subjectRef").asString() shouldBe ""
    }

    @Test
    fun `INV-AC-10588 a principal with no grant gets 403 SRT-004 on both operations and no row`() {
        val t = tenant(HANDLER to "privacy.handler", NOBODY to "no.such.role")
        val id = open(HANDLER, t).body.get("caseId").asString()
        val a = open(NOBODY, t, openBody("REQ-N"))
        val b = close(NOBODY, t, id)
        a.status shouldBe 403
        a.code shouldBe "SRT-004"
        b.status shouldBe 403
        b.code shouldBe "SRT-004"
        rows(t) shouldBe 1
        stored(t, "status") shouldBe "OPEN"
        close(HANDLER, t, id).status shouldBe 200
    }

    @Test
    fun `INV-AC-10588 the look-alike keys and a principal granted at scope ENTITY only are refused both operations`() {
        val t =
            tenant(
                HANDLER to "privacy.handler",
                "u-space" to "lookalike.space",
                "u-view" to "lookalike.view",
                "u-record" to "lookalike.record",
                READER to "privacy.reader",
            )
        service.grants.grant(
            TenantId(t),
            SCOPED,
            service.grants.role("privacy.handler", GrantScope.ENTITY, "ENT-A"),
        )
        val id = open(HANDLER, t).body.get("caseId").asString()
        listOf("u-space", "u-view", "u-record", READER, SCOPED).forEach {
            withClue(it) {
                val a = open(it, t, openBody("REQ-$it"))
                val b = close(it, t, id)
                a.status shouldBe 403
                a.code shouldBe "SRT-004"
                b.status shouldBe 403
                b.code shouldBe "SRT-004"
            }
        }
        rows(t) shouldBe 1
        stored(t, "status") shouldBe "OPEN"
    }

    @Test
    fun `INV-AC-10588 a principal named security officer whose decision is refuse is refused both operations`() {
        val t = tenant(HANDLER to "privacy.handler", OFFICER to "security.officer")
        val id = open(HANDLER, t).body.get("caseId").asString()
        open(OFFICER, t, openBody("REQ-O")).status shouldBe 403
        close(OFFICER, t, id).status shouldBe 403
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-10588 a grant in one tenant does not permit a call in another`() {
        val granted = tenant(HANDLER to "privacy.handler")
        val other = tenant(NOBODY to "no.such.role")
        open(HANDLER, granted).status shouldBe 201
        open(HANDLER, other).status shouldBe 403
        rows(other) shouldBe 0
    }

    private companion object {
        const val CASES_PATH = "/internal/v1/evidence/cases"
        const val RECEIVED = "2026-03-01"
        const val HANDLER = "u-handler"
        const val READER = "u-reader"
        const val POLICY = "u-policy"
        const val OFFICER = "u-officer"
        const val SCOPED = "u-scoped"
        const val NOBODY = "u-nobody"
        const val PACK_DAYS = 30
        const val NOTICE_LEAD_DEFAULT = 7
        const val SHORTER = 14
        const val LONGER_THAN_PACK = 45
        const val LEAD_BELOW_MIN = 5
        const val LEAD_LONGER = 14
    }
}
