package com.dynokore.gst.evidence.domain.cases

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CaseRecordTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val at = Instant.parse("2026-10-28T00:00:00Z")
    private val entry = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun facts(
        reviewReference: String = "R",
        ids: List<UUID> = listOf(entry),
        outcome: String = "O",
    ) = SecurityCaseFacts(reviewReference, ids, outcome, false)

    private fun security(
        status: CaseStatus = CaseStatus.OPEN,
        subjectRef: String? = null,
        security: SecurityCaseFacts? = facts(),
    ) = CaseRecord(id, CaseKind.SECURITY, status, subjectRef, at, security)

    private fun privacy(
        subjectRef: String? = "subject",
        security: SecurityCaseFacts? = null,
    ) = CaseRecord(id, CaseKind.PRIVACY, CaseStatus.OPEN, subjectRef, at, security)

    @Test
    fun `INV-AC-11696 CaseKind members in order`() {
        CaseKind.entries.map { it.name } shouldBe listOf("PRIVACY", "SECURITY")
    }

    @Test
    fun `INV-AC-11696 CaseStatus members in order`() {
        CaseStatus.entries.map { it.name } shouldBe listOf("OPEN", "EXPORT_READY", "CLOSED")
    }

    @Test
    fun `INV-AC-11696 SECURITY without facts is refused`() {
        shouldThrow<IllegalArgumentException> { security(security = null) }
    }

    @Test
    fun `INV-AC-11696 SECURITY with a subjectRef is refused`() {
        shouldThrow<IllegalArgumentException> { security(subjectRef = "x") }
    }

    @Test
    fun `INV-AC-11696 SECURITY with a status other than OPEN is refused`() {
        shouldThrow<IllegalArgumentException> { security(status = CaseStatus.CLOSED) }
        shouldThrow<IllegalArgumentException> { security(status = CaseStatus.EXPORT_READY) }
    }

    @Test
    fun `INV-AC-11696 PRIVACY with facts is refused`() {
        shouldThrow<IllegalArgumentException> { privacy(security = facts()) }
    }

    @Test
    fun `INV-AC-11696 PRIVACY without a subjectRef is refused`() {
        shouldThrow<IllegalArgumentException> { privacy(subjectRef = null) }
    }

    @Test
    fun `INV-AC-11696 PRIVACY subjectRef of 61 characters is refused and 60 passes`() {
        shouldThrow<IllegalArgumentException> { privacy(subjectRef = "s".repeat(61)) }
        privacy(subjectRef = "s".repeat(60)).subjectRef!!.length shouldBe 60
    }

    @Test
    fun `INV-AC-11696 reviewReference of 101 characters is refused and 0 and 100 pass`() {
        shouldThrow<IllegalArgumentException> { facts(reviewReference = "r".repeat(101)) }
        facts(reviewReference = "").reviewReference shouldBe ""
        facts(reviewReference = "r".repeat(100)).reviewReference.length shouldBe 100
    }

    @Test
    fun `INV-AC-11696 outcome of 201 characters is refused and 0 and 200 pass`() {
        shouldThrow<IllegalArgumentException> { facts(outcome = "o".repeat(201)) }
        facts(outcome = "").outcome shouldBe ""
        facts(outcome = "o".repeat(200)).outcome.length shouldBe 200
    }

    @Test
    fun `INV-AC-11696 an empty id list is refused and one element passes`() {
        shouldThrow<IllegalArgumentException> { facts(ids = emptyList()) }
        facts(ids = listOf(entry)).auditLogEntryIds shouldBe listOf(entry)
    }

    @Test
    fun `INV-AC-11696 the id list keeps order and duplicates`() {
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        val given = listOf(other, entry, other)
        facts(ids = given).auditLogEntryIds shouldBe given
    }
}
