package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.override.ApproveOverride
import com.dynokore.gst.payload.application.override.AuditTrail
import com.dynokore.gst.payload.application.override.BlockingFindings
import com.dynokore.gst.payload.application.override.DocumentVersionFacts
import com.dynokore.gst.payload.application.override.DocumentVersions
import com.dynokore.gst.payload.application.override.FindingDisposition
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.OverrideStore
import com.dynokore.gst.payload.application.override.ReadOverrides
import com.dynokore.gst.payload.application.override.ReleaseDecisions
import com.dynokore.gst.payload.application.override.ReleaseRequest
import com.dynokore.gst.payload.application.override.RequestOverride
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.application.override.VALIDATION_OVERRIDE
import com.dynokore.gst.payload.contract.model.OverrideValidationRequest
import com.dynokore.gst.payload.contract.model.PendingValidationOverride
import com.dynokore.gst.payload.contract.model.ValidationOverride
import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private class Versions : DocumentVersions {
    val facts = mutableMapOf<Pair<UUID, Int>, DocumentVersionFacts>()

    override fun find(
        docUuid: UUID,
        version: Int,
    ) = facts[docUuid to version]
}

private class Authority(
    val holders: Set<String>,
) : OverrideAuthority {
    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ) = permission == VALIDATION_OVERRIDE && userId in holders
}

private class Findings : BlockingFindings {
    val overridable = mutableSetOf<Triple<UUID, Int, String>>()

    override fun disposition(
        docUuid: UUID,
        version: Int,
        code: String,
    ) = if (Triple(docUuid, version, code) in overridable) {
        FindingDisposition.OVERRIDABLE
    } else {
        FindingDisposition.REFUSED
    }
}

/** access-svc's decision: a threshold of SGD 1000.00, the first approver alone below or at it. */
private class Decisions(
    var answer: ((ReleaseRequest) -> ReleaseOutcome)? = null,
    val thresholdSgd: BigDecimal? = null,
) : ReleaseDecisions {
    override fun decide(request: ReleaseRequest): ReleaseOutcome =
        answer?.invoke(request) ?: when {
            request.approverRef in request.priorApproverRefs -> ReleaseOutcome.Denied("SAME_PERSON_TWICE")
            request.priorApproverRefs.isNotEmpty() -> ReleaseOutcome.Released
            thresholdSgd != null && request.totalSgd > thresholdSgd -> ReleaseOutcome.AwaitsSecond
            else -> ReleaseOutcome.Released
        }
}

private class Store : OverrideStore {
    val rows = linkedMapOf<Triple<UUID, Int, String>, StoredOverride>()
    private var tick = 0L

    override fun insert(override: NewOverride): StoredOverride? {
        val key = Triple(override.docUuid, override.version, override.code)
        if (key in rows) return null
        val now = Instant.EPOCH.plusSeconds(tick++)
        return StoredOverride(
            override.docUuid,
            override.version,
            override.code,
            override.reason,
            override.status,
            override.requestedBy,
            now,
            override.releasedBy,
            now.takeIf { override.releasedBy != null },
        ).also { rows[key] = it }
    }

    override fun find(
        docUuid: UUID,
        version: Int,
        code: String,
    ) = rows[Triple(docUuid, version, code)]

    override fun release(
        docUuid: UUID,
        version: Int,
        code: String,
        releasedBy: String,
    ): StoredOverride? {
        val key = Triple(docUuid, version, code)
        val row = rows[key]?.takeIf { it.status == OverrideStatus.PENDING } ?: return null
        return row
            .copy(
                status = OverrideStatus.RELEASED,
                releasedBy = releasedBy,
                releasedAt = Instant.EPOCH.plusSeconds(tick++),
            ).also { rows[key] = it }
    }

    override fun entries(
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ) = rows.values
        .filter { it.docUuid == docUuid && it.version == version }
        .sortedWith(compareBy({ it.requestedAt }, { it.code }))
        .filter {
            after == null ||
                compareValuesBy(it.requestedAt to it.code, after, { p -> p.first }, { p -> p.second }) > 0
        }.take(limit)
}

private class Audit : AuditTrail {
    val entries = mutableListOf<Triple<String, String, String>>()

    override fun requested(
        userId: String,
        override: StoredOverride,
    ) {
        entries += Triple("requested", userId, override.reason)
    }

    override fun released(
        userId: String,
        override: StoredOverride,
    ) {
        entries += Triple("released", userId, override.reason)
    }
}

class ValidationOverrideControllerTest {
    private val json = JsonMapper.builder().build()
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val versions = Versions()
    private val findings = Findings()
    private val store = Store()
    private val audit = Audit()
    private val authority = Authority(setOf("A1", "A2", "A3"))
    private var decisions = Decisions()
    private var caller: String? = "A1"

    private fun sut(): ValidationOverrideController {
        val http =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                val key = args?.firstOrNull()
                if (method.name == "getAttribute" && key == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) {
                    caller?.let { EndUserPrincipal(it, null, emptySet()) }
                } else {
                    null
                }
            } as HttpServletRequest
        return ValidationOverrideController(
            RequestOverride(versions, authority, findings, decisions, store, audit),
            ApproveOverride(versions, authority, decisions, store, audit),
            ReadOverrides(versions, authority, store),
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            StaticListableBeanFactory(mapOf("request" to http)).getBeanProvider(HttpServletRequest::class.java),
        )
    }

    private fun held(
        doc: UUID,
        version: Int = 1,
        total: String = "100.00",
    ) {
        versions.facts[doc to version] = DocumentVersionFacts("E-001", BigDecimal(total))
    }

    private fun blocked(
        doc: UUID,
        version: Int = 1,
        code: String = "PAY-011",
        total: String = "100.00",
    ) {
        held(doc, version, total)
        findings.overridable += Triple(doc, version, code)
    }

    private fun override(
        user: String,
        doc: UUID,
        version: Int = 1,
        code: String = "PAY-011",
        reason: String = "Register lag",
    ): ResponseEntity<ValidationOverride> {
        caller = user
        return sut().overrideValidation(doc, version, OverrideValidationRequest(code, reason))
    }

    private fun approve(
        user: String,
        doc: UUID,
        version: Int = 1,
        code: String = "PAY-011",
    ): ResponseEntity<ValidationOverride> {
        caller = user
        return sut().approveValidationOverride(doc, version, code)
    }

    private fun body(response: ResponseEntity<*>): JsonNode = json.readTree(response.body as String)

    private fun ResponseEntity<*>.problem(
        status: Int,
        code: String,
        reasonCode: String? = null,
    ) {
        statusCode.value() shouldBe status
        headers.contentType?.toString().orEmpty() shouldBe "application/problem+json"
        body(this)["code"].asString() shouldBe code
        body(this)["reasonCode"]?.asString() shouldBe reasonCode
    }

    @Test
    fun `INV-AC-11512 a PAY-011 block is released only with a reason and only by the permission holder`() {
        blocked(d1)
        override("A1", d1, reason = "").problem(400, "SRT-001")
        store.rows shouldBe emptyMap()
        override("B9", d1).problem(403, "SRT-004")
        store.rows shouldBe emptyMap()
        override("A1", d1, code = "PAY-0017").problem(422, "PAY-017")
        store.rows shouldBe emptyMap()
        val released = override("A1", d1)
        released.statusCode.value() shouldBe 201
        released.body!!.overriddenBy shouldBe "A1"
        audit.entries.map { it.first to it.second } shouldBe listOf("requested" to "A1", "released" to "A1")
    }

    @Test
    fun `INV-AC-11512 an awaited second approver is 202 with a PENDING row and the document stays blocked`() {
        blocked(d1, total = "1000.01")
        decisions = Decisions(thresholdSgd = BigDecimal("1000.00"))
        val waiting = override("A1", d1)
        waiting.statusCode.value() shouldBe 202
        waiting.body.shouldBeInstanceOf<PendingValidationOverride>()
        store.find(d1, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
        audit.entries.map { it.first } shouldBe listOf("requested")
    }

    @Test
    fun `a missing permission is 403 SRT-004 without reasonCode and stores nothing`() {
        blocked(d1)
        val response = override("B9", d1)
        response.problem(403, "SRT-004")
        body(response).has("reasonCode") shouldBe false
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `a code the catalogue holds as MANUAL or TERMINAL, and its look-alikes, is 422 PAY-017`() {
        blocked(d1)
        for (code in listOf("PAY-001", "PAY-008", "PAY-0017", "pay-001")) {
            override("A1", d1, code = code).problem(422, "PAY-017")
        }
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `a code the findings of that exact version do not carry is 422 PAY-017 and nothing is stored`() {
        blocked(d1, version = 1)
        held(d1, version = 2)
        override("A1", d1, version = 2).problem(422, "PAY-017")
        held(d2)
        override("A1", d2).problem(422, "PAY-017")
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `a version payload-svc does not hold is 404 SRT-005 and nothing is stored`() {
        override("A1", d1).problem(404, "SRT-005")
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `NotOverridable from the decision is 422 PAY-017`() {
        blocked(d1)
        decisions = Decisions({ ReleaseOutcome.Denied("NOT_OVERRIDABLE") })
        override("A1", d1).problem(422, "PAY-017")
        store.rows shouldBe emptyMap()
    }

    @Test
    fun `Refused ACCESS_DECISION_UNAVAILABLE is 403 SRT-004 with that reasonCode and nothing is stored`() {
        blocked(d1)
        decisions = Decisions({ ReleaseOutcome.Denied("ACCESS_DECISION_UNAVAILABLE") })
        override("A1", d1).problem(403, "SRT-004", "ACCESS_DECISION_UNAVAILABLE")
        store.rows shouldBe emptyMap()
        audit.entries shouldBe emptyList()
    }

    @Test
    fun `the look-alike ACCESS_DECISION_UNAVAILABLE with a trailing space is NOT_OVERRIDABLE`() {
        blocked(d1)
        decisions = Decisions({ ReleaseOutcome.Denied("ACCESS_DECISION_UNAVAILABLE ") })
        override("A1", d1).problem(403, "SRT-004", "NOT_OVERRIDABLE")
    }

    @Test
    fun `the look-alike access_decision_unavailable in lower case is NOT_OVERRIDABLE`() {
        blocked(d1)
        decisions = Decisions({ ReleaseOutcome.Denied("access_decision_unavailable") })
        override("A1", d1).problem(403, "SRT-004", "NOT_OVERRIDABLE")
    }

    @Test
    fun `a Refused result is 403 SRT-004 with reasonCode only for the known codes, else NOT_OVERRIDABLE`() {
        blocked(d1)
        val expected =
            mapOf(
                "SAME_PERSON_TWICE" to "SAME_PERSON_TWICE",
                "NO_SOLE_OPERATOR_ACK" to "NO_SOLE_OPERATOR_ACK",
                "same_person_twice" to "NOT_OVERRIDABLE",
                "SAME_PERSON_TWICE " to "NOT_OVERRIDABLE",
                "SOMETHING_ELSE" to "NOT_OVERRIDABLE",
            )
        for ((answered, sent) in expected) {
            decisions = Decisions({ ReleaseOutcome.Denied(answered) })
            override("A1", d1).problem(403, "SRT-004", sent)
        }
        store.rows shouldBe emptyMap()
        audit.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-9561 at the boundary D1 is released, D2 waits, the same person twice is refused, A2 releases`() {
        blocked(d1, total = "1000.00")
        blocked(d2, total = "1000.01")
        decisions = Decisions(thresholdSgd = BigDecimal("1000.00"))
        val results = mutableListOf<String>()
        results += if (override("A1", d1).statusCode.value() == 201) "released" else "other"
        results += if (override("A1", d2).statusCode.value() == 202) "waiting" else "other"
        val again = approve("A1", d2)
        again.problem(403, "SRT-004", "SAME_PERSON_TWICE")
        results += if (store.find(d2, 1, "PAY-011")!!.status == OverrideStatus.PENDING) "waiting" else "other"
        val second = approve("A2", d2)
        results += if (second.statusCode.value() == 201 && second.body!!.overriddenBy == "A2") "released" else "other"
        results shouldBe listOf("released", "waiting", "waiting", "released")
    }

    @Test
    fun `INV-AC-2279 with nothing set one approver releases an OVERRIDABLE code and a TERMINAL one is refused`() {
        blocked(d1)
        override("A1", d1).statusCode.value() shouldBe 201
        override("A1", d1, code = "PAY-008").problem(422, "PAY-017")
    }

    @Test
    fun `approve is 404 for no pending row, a released row, another tenant's row and an unknown version`() {
        blocked(d1)
        approve("A2", d1).problem(404, "SRT-005")
        override("A1", d1).statusCode.value() shouldBe 201
        approve("A2", d1).problem(404, "SRT-005")
        approve("A2", d2).problem(404, "SRT-005")
        approve("A2", d1, code = "pay-011").problem(404, "SRT-005")
    }

    @Test
    fun `approve is 403 without the permission (no reasonCode), and for a DENY or another approver awaited`() {
        blocked(d1, total = "1000.01")
        decisions = Decisions(thresholdSgd = BigDecimal("1000.00"))
        override("A1", d1).statusCode.value() shouldBe 202
        val noPermission = approve("B9", d1)
        noPermission.problem(403, "SRT-004")
        body(noPermission).has("reasonCode") shouldBe false
        decisions = Decisions({ ReleaseOutcome.Denied("NO_SOLE_OPERATOR_ACK") })
        approve("A2", d1).problem(403, "SRT-004", "NO_SOLE_OPERATOR_ACK")
        decisions = Decisions({ ReleaseOutcome.AwaitsSecond })
        approve("A2", d1).problem(403, "SRT-004", "NOT_OVERRIDABLE")
        store.find(d1, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `approve with Refused ACCESS_DECISION_UNAVAILABLE is 403 with that reasonCode, the row stays PENDING`() {
        blocked(d1, total = "1000.01")
        decisions = Decisions(thresholdSgd = BigDecimal("1000.00"))
        override("A1", d1).statusCode.value() shouldBe 202
        decisions = Decisions({ ReleaseOutcome.Denied("ACCESS_DECISION_UNAVAILABLE") })
        approve("A2", d1).problem(403, "SRT-004", "ACCESS_DECISION_UNAVAILABLE")
        store.find(d1, 1, "PAY-011")!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `getValidationOverrides lists one version, pages by cursor, refuses bad caller, cursor and version`() {
        blocked(d1)
        findings.overridable += Triple(d1, 1, "PAY-012")
        override("A1", d1).statusCode.value() shouldBe 201
        override("A1", d1, code = "PAY-012").statusCode.value() shouldBe 201
        caller = "A3"
        val all = sut().getValidationOverrides(d1, 1, 100, null)
        all.statusCode.value() shouldBe 200
        all.body!!.items.map { it.code } shouldBe listOf("PAY-011", "PAY-012")
        all.body!!.nextCursor shouldBe null
        val first = sut().getValidationOverrides(d1, 1, 1, null)
        first.body!!.items.map { it.code } shouldBe listOf("PAY-011")
        val second = sut().getValidationOverrides(d1, 1, 1, first.body!!.nextCursor)
        second.body!!.items.map { it.code } shouldBe listOf("PAY-012")
        second.body!!.nextCursor shouldBe null
        sut().getValidationOverrides(d1, 1, 1, "not-a-cursor").problem(400, "SRT-001")
        caller = "B9"
        sut().getValidationOverrides(d1, 1, 100, null).problem(403, "SRT-004")
        caller = "A1"
        sut().getValidationOverrides(d2, 1, 100, null).problem(404, "SRT-005")
    }

    @Test
    fun `a call with no verified principal is 401 SRT-003`() {
        blocked(d1)
        caller = null
        sut().overrideValidation(d1, 1, OverrideValidationRequest("PAY-011", "x")).problem(401, "SRT-003")
        store.rows shouldBe emptyMap()
    }
}
