package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.gst.evidence.syncedge.ContractDocument
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One role per permission key, so that a principal can hold one key and not another (the production matrix gives
 * `security.officer` both). Only [CaseEndpointsTest] adds it.
 */
@Configuration(proxyBeanMethods = false)
class CaseRoleMapBeans {
    @Bean
    @Primary
    fun caseRoleMap(): OperationRoleMap =
        OperationRoleMap {
            when (it) {
                RECORD -> mapOf("case.recorder" to CellAllowance.PERMITTED)
                READ -> mapOf("case.reader" to CellAllowance.PERMITTED)
                HANDLE -> mapOf("case.handler" to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }

    companion object {
        const val RECORD = "security-case.record"
        const val READ = "privacy-case.read"
        const val HANDLE = "privacy-request.handle"
    }
}

/**
 * INV-AC-11698 over HTTP: `recordSecurityCase` and `getCase` in the service as TPL-18 composes it, over the real
 * PostgreSQL. The caller of every request is the end user of a signed token; the permission of each principal is the
 * TENANT-scope grant fed to the service's one `AuthorityReplica`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass", "TooManyFunctions") // one HTTP test class for the two operations of one task
class CaseEndpointsTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("cet")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, CaseRoleMapBeans::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
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

    private fun record(
        user: String,
        tenant: UUID,
        body: String = request(),
        key: String? = UUID.randomUUID().toString(),
    ) = send("POST", RECORD_PATH, user, tenant, body, key)

    private fun get(
        user: String,
        tenant: UUID,
        caseId: String,
    ) = send("GET", "$CASES_PATH/$caseId", user, tenant)

    private fun request(
        reviewReference: String = "REVIEW-1",
        ids: List<UUID> = listOf(UUID.randomUUID()),
        outcome: String = "keys revoked",
        exposed: Boolean = false,
    ): String {
        val node = mapper.createObjectNode()
        node.put("reviewReference", reviewReference)
        node.putArray("auditLogEntryIds").also { array -> ids.forEach { array.add(it.toString()) } }
        node.put("outcome", outcome)
        node.put("personalDataExposed", exposed)
        return mapper.writeValueAsString(node)
    }

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

    private fun rows(tenant: UUID): Int =
        scalar("select count(*) from evidence.case_register where tenant_id = ?", tenant).toInt()

    private fun scalar(
        sql: String,
        vararg args: Any,
    ): String =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun instant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    @Test
    fun `INV-AC-11698 the permit principal records a case and recordedAt equals the stored opened_at`() {
        val t = tenant(RECORDER to "case.recorder")
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        val answer = record(RECORDER, t, request("REVIEW-9", ids, "keys revoked", exposed = true))
        answer.status shouldBe 201
        answer.body.get("reviewReference").asString() shouldBe "REVIEW-9"
        answer.body.get("outcome").asString() shouldBe "keys revoked"
        answer.body.get("breachAssessmentOpened").asBoolean() shouldBe true
        rows(t) shouldBe 1
        val storedMicros =
            scalar(
                "select (extract(epoch from opened_at) * 1000000)::bigint " +
                    "from evidence.case_register where tenant_id = ?",
                t,
            )
        val recorded = instant(answer.body.get("recordedAt").asString())
        (recorded.epochSecond * MICROS + recorded.nano / NANOS_PER_MICRO).toString() shouldBe storedMicros
    }

    @Test
    fun `INV-AC-11698 breachAssessmentOpened equals personalDataExposed both ways`() {
        val t = tenant(RECORDER to "case.recorder")
        record(RECORDER, t, request(exposed = false)).body.get("breachAssessmentOpened").asBoolean() shouldBe false
        record(RECORDER, t, request(exposed = true)).body.get("breachAssessmentOpened").asBoolean() shouldBe true
    }

    @Test
    fun `INV-AC-11698 the same key and body twice leave one row and the second answer is 200 with the same fields`() {
        val t = tenant(RECORDER to "case.recorder")
        val key = UUID.randomUUID().toString()
        val body = request()
        val first = record(RECORDER, t, body, key)
        val second = record(RECORDER, t, body, key)
        first.status shouldBe 201
        second.status shouldBe 200
        second.body.get("caseId").asString() shouldBe first.body.get("caseId").asString()
        instant(second.body.get("recordedAt").asString()) shouldBe instant(first.body.get("recordedAt").asString())
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-11698 the same key with another outcome answers 422 SRT-009 and leaves one row`() {
        val t = tenant(RECORDER to "case.recorder")
        val key = UUID.randomUUID().toString()
        record(RECORDER, t, request(outcome = "one"), key).status shouldBe 201
        val other = record(RECORDER, t, request(outcome = "two"), key)
        other.status shouldBe 422
        other.code shouldBe "SRT-009"
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-11698 two keys with the same reviewReference leave two rows with two caseIds`() {
        val t = tenant(RECORDER to "case.recorder")
        val a = record(RECORDER, t, request("SAME"))
        val b = record(RECORDER, t, request("SAME"))
        a.status shouldBe 201
        b.status shouldBe 201
        (a.body.get("caseId").asString() == b.body.get("caseId").asString()) shouldBe false
        rows(t) shouldBe 2
    }

    @Test
    fun `INV-AC-11698 a missing or malformed Idempotency-Key answers 400 SRT-002 and stores nothing`() {
        val t = tenant(RECORDER to "case.recorder")
        listOf(null, "not-a-uuid").forEach {
            val answer = record(RECORDER, t, key = it)
            withClue("key $it") {
                answer.status shouldBe 400
                answer.code shouldBe "SRT-002"
            }
        }
        rows(t) shouldBe 0
    }

    @Test
    fun `INV-AC-11698 auditLogEntryIds come back in the order sent with duplicates kept`() {
        val t = tenant(RECORDER to "case.recorder")
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val sent = listOf(b, a, b, a)
        val answer = record(RECORDER, t, request(ids = sent))
        val returned = answer.body.get("auditLogEntryIds")
        List(returned.size()) { returned.get(it).asString() } shouldContainExactly sent.map { it.toString() }
        val stored =
            scalar(
                "select array_to_string(audit_log_entry_ids, ',') from evidence.case_register where tenant_id = ?",
                t,
            )
        stored shouldBe sent.joinToString(",")
    }

    @Test
    fun `INV-AC-11698 an empty auditLogEntryIds or an outcome of 201 characters is refused with no row`() {
        val t = tenant(RECORDER to "case.recorder")
        val empty = record(RECORDER, t, request(ids = emptyList()))
        empty.status shouldBe 400
        empty.code shouldBe "SRT-001"
        val long = record(RECORDER, t, request(outcome = "x".repeat(OUTCOME_TOO_LONG)))
        long.status shouldBe 400
        long.code shouldBe "SRT-001"
        rows(t) shouldBe 0
        record(RECORDER, t, request(outcome = "x".repeat(OUTCOME_MAX))).status shouldBe 201
        rows(t) shouldBe 1
    }

    @Test
    fun `INV-AC-11698 getCase of a recorded case answers the four members only`() {
        val t = tenant(RECORDER to "case.recorder", READER to "case.reader")
        val recorded = record(RECORDER, t, request(exposed = true))
        val id = recorded.body.get("caseId").asString()
        val answer = get(READER, t, id)
        answer.status shouldBe 200
        answer.body.propertyNames().toSet() shouldBe setOf("caseId", "subjectRef", "status", "openedAt")
        answer.body.get("caseId").asString() shouldBe id
        answer.body.get("subjectRef").asString() shouldBe ""
        answer.body.get("status").asString() shouldBe "OPEN"
        instant(answer.body.get("openedAt").asString()) shouldBe instant(recorded.body.get("recordedAt").asString())
    }

    @Test
    fun `INV-AC-11698 getCase of an unknown id and of another tenant's case answer the same 404`() {
        val t = tenant(RECORDER to "case.recorder", READER to "case.reader")
        val other = tenant(READER to "case.reader")
        val id = record(RECORDER, t).body.get("caseId").asString()
        val foreign = get(READER, other, id)
        val unknown = get(READER, t, UUID.randomUUID().toString())
        foreign.status shouldBe 404
        unknown.status shouldBe 404
        foreign.code shouldBe "SRT-005"
        unknown.code shouldBe "SRT-005"
        bare(foreign.body) shouldBe bare(unknown.body)
        get(READER, t, id).status shouldBe 200
    }

    private fun bare(body: JsonNode): JsonNode {
        val copy = body.deepCopy() as tools.jackson.databind.node.ObjectNode
        PER_REQUEST.forEach { copy.remove(it) }
        return copy
    }

    @Test
    fun `INV-AC-11698 a principal with no grant gets 403 SRT-004 on both operations and no row`() {
        val t = tenant(RECORDER to "case.recorder")
        val id = record(RECORDER, t).body.get("caseId").asString()
        val before = rows(t)
        val denied = record(NOBODY, t)
        denied.status shouldBe 403
        denied.code shouldBe "SRT-004"
        rows(t) shouldBe before
        val read = get(NOBODY, t, id)
        read.status shouldBe 403
        read.code shouldBe "SRT-004"
        read.body.has("subjectRef") shouldBe false
    }

    @Test
    fun `INV-AC-11698 a principal who holds privacy-request handle but not security-case record is refused record`() {
        val t = tenant(HANDLER to "case.handler")
        val answer = record(HANDLER, t)
        answer.status shouldBe 403
        answer.code shouldBe "SRT-004"
        rows(t) shouldBe 0
    }

    @Test
    fun `INV-AC-11698 a principal who holds privacy-case read but not security-case record reads and cannot record`() {
        val t = tenant(RECORDER to "case.recorder", READER to "case.reader")
        val id = record(RECORDER, t).body.get("caseId").asString()
        val before = rows(t)
        val denied = record(READER, t)
        denied.status shouldBe 403
        denied.code shouldBe "SRT-004"
        rows(t) shouldBe before
        get(READER, t, id).status shouldBe 200
    }

    @Test
    fun `INV-AC-11698 a principal who holds record or handle but not privacy-case read is refused getCase`() {
        val t = tenant(RECORDER to "case.recorder", HANDLER to "case.handler")
        val id = record(RECORDER, t).body.get("caseId").asString()
        listOf(RECORDER, HANDLER).forEach {
            val answer = get(it, t, id)
            answer.status shouldBe 403
            answer.code shouldBe "SRT-004"
        }
    }

    @Test
    fun `INV-AC-11698 a principal whose role is named security officer but whose decision is refuse is refused both`() {
        val t = tenant(RECORDER to "case.recorder", OFFICER to "security.officer")
        val id = record(RECORDER, t).body.get("caseId").asString()
        val before = rows(t)
        record(OFFICER, t).status shouldBe 403
        get(OFFICER, t, id).status shouldBe 403
        rows(t) shouldBe before
    }

    @Test
    fun `INV-AC-11698 a principal whose only grant is at scope ENTITY is refused both operations`() {
        val t = tenant(RECORDER to "case.recorder")
        listOf("case.recorder", "case.reader").forEach {
            service.grants.grant(TenantId(t), SCOPED, service.grants.role(it, GrantScope.ENTITY, "ENT-A"))
        }
        val id = record(RECORDER, t).body.get("caseId").asString()
        val before = rows(t)
        record(SCOPED, t).status shouldBe 403
        get(SCOPED, t, id).status shouldBe 403
        rows(t) shouldBe before
    }

    @Test
    fun `INV-AC-11698 a grant in one tenant does not permit a call in another`() {
        val granted = tenant(RECORDER to "case.recorder")
        val other = tenant()
        record(RECORDER, other).status shouldBe 403
        rows(other) shouldBe 0
        record(RECORDER, granted).status shouldBe 201
    }

    @Test
    fun `INV-AC-11698 CaseStatus names equal the contract status enum as ordered lists`() {
        val factory = YamlMapFactoryBean()
        factory.setResources(ByteArrayResource(ContractDocument.text(javaClass.classLoader).toByteArray()))
        val document = checkNotNull(factory.getObject())

        fun child(
            node: Any?,
            name: String,
        ): Any? = (node as Map<*, *>)[name]
        val properties = child(child(child(child(document, "components"), "schemas"), "PrivacyCase"), "properties")
        val values = child(child(properties, "status"), "enum") as List<*>
        CaseStatus.entries.map { it.name } shouldBe values.map { it.toString() }
    }

    @Test
    fun `INV-AC-11698 the production permission matrix gives security officer both keys`() {
        val production =
            service
                .beansOf(OperationRoleMap::class.java)
                .filterKeys { it != "caseRoleMap" }
                .values
                .single()
        listOf(CaseRoleMapBeans.RECORD, CaseRoleMapBeans.READ).forEach {
            production.rolesFor(it).keys shouldBe setOf("security.officer")
        }
    }

    private companion object {
        const val RECORD_PATH = "/internal/v1/evidence/security-cases"
        const val CASES_PATH = "/internal/v1/evidence/cases"
        const val RECORDER = "u-recorder"
        const val READER = "u-reader"
        const val HANDLER = "u-handler"
        const val OFFICER = "u-officer"
        const val SCOPED = "u-scoped"
        const val NOBODY = "u-nobody"
        const val OUTCOME_MAX = 200
        const val OUTCOME_TOO_LONG = 201
        const val MICROS = 1_000_000L
        const val NANOS_PER_MICRO = 1_000
        val PER_REQUEST = listOf("correlationId", "instance")
    }
}
