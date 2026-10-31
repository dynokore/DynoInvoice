package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqCaseStore
import com.dynokore.gst.evidence.domain.cases.CaseClosure
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.PrivacyCaseFacts
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * INV-AC-11708 (INV-Q-11688 A, INV-Q-6834 A, INV-Q-6836 A): the privacy columns of `case_register` hold the deadline
 * immutably under forced tenant isolation, and `JooqCaseStore` stores, closes and marks a privacy record.
 */
class PrivacyCaseTableTest {
    private val owner = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val openedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun facts(
        reference: String = "REQ-${UUID.randomUUID()}",
        closure: CaseClosure? = null,
        prompted: Instant? = null,
    ) = PrivacyCaseFacts(
        RequestKind.ACCESS,
        reference,
        LocalDate.parse("2026-03-01"),
        LocalDate.parse("2026-03-31"),
        30,
        closure,
        prompted,
    )

    private fun privacy(
        status: CaseStatus = CaseStatus.OPEN,
        facts: PrivacyCaseFacts = facts(),
    ) = CaseRecord(UUID.randomUUID(), CaseKind.PRIVACY, status, "subject-1", openedAt, null, facts)

    private fun <T> bound(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            block(connection).also { connection.commit() }
        }

    private fun store(connection: Connection) = JooqCaseStore(DSL.using(connection, SQLDialect.POSTGRES))

    /** The SQLSTATE a raw statement is refused with as the runtime login bound to [owner]; rolled back. */
    private fun refusedWith(sql: String): String? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, owner)
            val result = runCatching { connection.createStatement().use { it.execute(sql) } }
            connection.rollback()
            result.exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    @Suppress("LongParameterList")
    private fun insert(
        kind: String = "PRIVACY",
        status: String = "OPEN",
        subjectRef: String = "'s'",
        requestKind: String = "'ACCESS'",
        reference: String = "gen_random_uuid()::text",
        period: String = "30",
        tenant: UUID = owner,
        closure: String = "null, null, null",
        review: String = "null, null, null, null",
    ) = "insert into evidence.case_register (tenant_id, case_id, case_kind, status, subject_ref, opened_at, " +
        "request_kind, request_reference, received_on, response_deadline, deadline_period_days, closed_at, " +
        "closure_outcome, closure_basis, review_reference, audit_log_entry_ids, outcome, " +
        "breach_assessment_opened) values ('$tenant', gen_random_uuid(), '$kind', '$status', $subjectRef, now(), " +
        "$requestKind, $reference, date '2026-03-01', date '2026-03-31', $period, $closure, $review)"

    private fun seeded(): UUID {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        return record.caseId
    }

    @Test
    fun `INV-AC-11708 the valid raw PRIVACY row is accepted, so each refusal below is due to its own check`() {
        refusedWith(insert()) shouldBe null
    }

    @Test
    fun `INV-AC-11708 a PRIVACY row without a request_reference is refused`() {
        refusedWith(insert(reference = "null")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11708 a SECURITY row with a request_kind is refused`() {
        val security = "'R', array[gen_random_uuid()], 'ok', false"
        refusedWith(insert(kind = "SECURITY", subjectRef = "null", reference = "null", review = security)) shouldBe
            "23514"
        refusedWith(
            insert(
                kind = "SECURITY",
                subjectRef = "null",
                requestKind = "null",
                reference = "null",
                period = "null",
                review = security,
            ).replace("date '2026-03-01', date '2026-03-31'", "null, null"),
        ) shouldBe null
    }

    @Test
    fun `INV-AC-11708 a request_kind outside the list and a period of 0 are refused`() {
        refusedWith(insert(requestKind = "'OTHER'")) shouldBe "23514"
        refusedWith(insert(period = "0")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11708 a closure_basis of 501 and of 0 characters is refused, 500 is accepted`() {
        val closed = { basis: String -> "now(), 'ANSWERED', '$basis'" }
        refusedWith(insert(status = "CLOSED", closure = closed("b".repeat(501)))) shouldBe "23514"
        refusedWith(insert(status = "CLOSED", closure = closed(""))) shouldBe "23514"
        refusedWith(insert(status = "CLOSED", closure = closed("b".repeat(500)))) shouldBe null
    }

    @Test
    fun `INV-AC-11708 CLOSED without closure and closure while OPEN are refused`() {
        refusedWith(insert(status = "CLOSED")) shouldBe "23514"
        refusedWith(insert(status = "OPEN", closure = "now(), 'ANSWERED', 'done'")) shouldBe "23514"
        refusedWith(insert(status = "CLOSED", closure = "now(), null, null")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11708 a PRIVACY row with an empty subject_ref is refused`() {
        refusedWith(insert(subjectRef = "''")) shouldBe "23514"
    }

    @Test
    fun `INV-AC-11708 a duplicate request reference of the tenant is refused`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        val duplicate = insert(reference = "'${record.privacy!!.requestReference}'")
        refusedWith(duplicate) shouldBe "23505"
    }

    @Test
    fun `INV-AC-11708 UPDATE of response_deadline, deadline_period_days and received_on is refused`() {
        seeded()
        refusedWith("update evidence.case_register set response_deadline = date '2026-04-30'") shouldBe "42501"
        refusedWith("update evidence.case_register set deadline_period_days = 60") shouldBe "42501"
        refusedWith("update evidence.case_register set received_on = date '2026-02-01'") shouldBe "42501"
        refusedWith("update evidence.case_register set request_kind = 'CORRECTION'") shouldBe "42501"
        refusedWith("update evidence.case_register set request_reference = 'x'") shouldBe "42501"
        refusedWith("update evidence.case_register set subject_ref = 'x'") shouldBe "42501"
    }

    @Test
    fun `INV-AC-11708 DELETE is refused`() {
        seeded()
        refusedWith("delete from evidence.case_register") shouldBe "42501"
    }

    @Test
    fun `INV-AC-11708 an insert with another tenant than the bound one is refused by the policy`() {
        refusedWith(insert(tenant = other)) shouldBe "42501"
    }

    @Test
    fun `INV-AC-11708 a PRIVACY record reads back equal for its tenant and not at all for another`() {
        val record = privacy(facts = facts(prompted = null))
        bound(owner) { store(it).insert(TenantId(owner), record) }
        bound(owner) { store(it).find(TenantId(owner), record.caseId) } shouldBe record
        val reference = record.privacy!!.requestReference
        bound(owner) { store(it).findByRequestReference(TenantId(owner), reference) } shouldBe record
        bound(other) { store(it).findByRequestReference(TenantId(other), reference) } shouldBe null
        bound(other) { store(it).findByRequestReference(TenantId(owner), reference) } shouldBe null
    }

    @Test
    fun `INV-AC-11708 a second insert of the same tenant and request reference throws`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        val again = privacy(facts = facts(reference = record.privacy!!.requestReference))
        shouldThrow<DataAccessException> { bound(owner) { store(it).insert(TenantId(owner), again) } }
    }

    @Test
    fun `INV-AC-11708 update with a closure stores CLOSED and the closure and changes no other column`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        val closure = CaseClosure(openedAt.plusSeconds(60), CaseOutcome.REFUSED, "no personal data held")
        val closed = record.copy(status = CaseStatus.CLOSED, privacy = record.privacy!!.copy(closure = closure))
        bound(owner) { store(it).update(TenantId(owner), closed) }
        bound(owner) { store(it).find(TenantId(owner), record.caseId) } shouldBe closed
    }

    @Test
    fun `INV-AC-11708 update of a missing case throws`() {
        shouldThrow<IllegalStateException> { bound(owner) { store(it).update(TenantId(owner), privacy()) } }
    }

    @Test
    fun `INV-AC-11708 markNoticePrompted sets the instant once`() {
        val record = privacy()
        bound(owner) { store(it).insert(TenantId(owner), record) }
        val first = openedAt.plusSeconds(10)
        bound(owner) { store(it).markNoticePrompted(TenantId(owner), record.caseId, first) }
        bound(owner) { store(it).markNoticePrompted(TenantId(owner), record.caseId, first.plusSeconds(99)) }
        bound(owner) { store(it).find(TenantId(owner), record.caseId) }!!.privacy!!.noticePromptedAt shouldBe first
    }

    @Test
    fun `INV-AC-11708 findUnprompted returns the PRIVACY records not CLOSED with no marker, for the bound tenant`() {
        val tenant = UUID.randomUUID()
        val open = privacy()
        val exportReady = privacy(status = CaseStatus.EXPORT_READY)
        val prompted = privacy(facts = facts(prompted = openedAt))
        val closure = CaseClosure(openedAt, CaseOutcome.ANSWERED, "sent")
        val closed = privacy(status = CaseStatus.CLOSED, facts = facts(closure = closure))
        val security =
            CaseRecord(
                UUID.randomUUID(),
                CaseKind.SECURITY,
                CaseStatus.OPEN,
                null,
                openedAt,
                SecurityCaseFacts("R", listOf(UUID.randomUUID()), "ok", false),
            )
        val foreign = privacy()
        bound(tenant) { c ->
            listOf(open, exportReady, prompted, closed, security).forEach { store(c).insert(TenantId(tenant), it) }
        }
        bound(other) { store(it).insert(TenantId(other), foreign) }
        val found = bound(tenant) { store(it).findUnprompted(TenantId(tenant)) }
        found.map { it.caseId }.toSet() shouldBe setOf(open.caseId, exportReady.caseId)
        bound(other) { store(it).findUnprompted(TenantId(other)) }.map { it.caseId } shouldBe listOf(foreign.caseId)
    }
}
