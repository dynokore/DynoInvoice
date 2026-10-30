package com.dynokore.gst.evidence.domain.cases

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class PrivacyCaseFactsTest {
    private val at = Instant.parse("2026-03-01T00:00:00Z")
    private val received = LocalDate.parse("2026-03-01")

    private fun facts(
        reference: String = "REQ-1",
        deadline: LocalDate = LocalDate.parse("2026-03-31"),
        period: Int = 30,
        closure: CaseClosure? = null,
        prompted: Instant? = null,
    ) = PrivacyCaseFacts(RequestKind.ACCESS, reference, received, deadline, period, closure, prompted)

    @Test
    fun `INV-AC-11707 RequestKind and CaseOutcome members in order`() {
        RequestKind.entries.map { it.name } shouldBe listOf("ACCESS", "CORRECTION")
        CaseOutcome.entries.map { it.name } shouldBe listOf("ANSWERED", "REFUSED")
    }

    @Test
    fun `INV-AC-11707 requestReference of 0 and 101 characters is refused and 1 and 100 pass`() {
        shouldThrow<IllegalArgumentException> { facts(reference = "") }
        shouldThrow<IllegalArgumentException> { facts(reference = "r".repeat(101)) }
        facts(reference = "r").requestReference.length shouldBe 1
        facts(reference = "r".repeat(100)).requestReference.length shouldBe 100
    }

    @Test
    fun `INV-AC-11707 deadlinePeriodDays below 1 is refused`() {
        shouldThrow<IllegalArgumentException> { facts(period = 0) }
        facts(period = 1).deadlinePeriodDays shouldBe 1
    }

    @Test
    fun `INV-AC-11707 a deadline before receipt is refused and the receipt day passes`() {
        shouldThrow<IllegalArgumentException> { facts(deadline = received.minusDays(1)) }
        facts(deadline = received).responseDeadline shouldBe received
    }

    @Test
    fun `INV-AC-11707 a case may be prompted and closed`() {
        val closure = CaseClosure(at, CaseOutcome.ANSWERED, "b")
        val both = facts(closure = closure, prompted = at)
        both.closure shouldBe closure
        both.noticePromptedAt shouldBe at
    }

    @Test
    fun `INV-AC-11707 basis of 0 and 501 characters is refused and 1 and 500 pass`() {
        shouldThrow<IllegalArgumentException> { CaseClosure(at, CaseOutcome.REFUSED, "") }
        shouldThrow<IllegalArgumentException> { CaseClosure(at, CaseOutcome.REFUSED, "b".repeat(501)) }
        CaseClosure(at, CaseOutcome.REFUSED, "b").basis.length shouldBe 1
        CaseClosure(at, CaseOutcome.REFUSED, "b".repeat(500)).basis.length shouldBe 500
    }
}
