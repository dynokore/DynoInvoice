package com.dynokore.gst.submission.domain.deadline

import com.dynokore.gst.submission.domain.deadline.DueSource.FILING_DUE_DATE
import com.dynokore.gst.submission.domain.deadline.DueSource.FILING_FACT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SubmissionDeadlineTest {
    private val due = LocalDate.of(2026, 4, 30)
    private val apr20 = LocalDate.of(2026, 4, 20)
    private val apr22 = LocalDate.of(2026, 4, 22)

    private fun confirm(
        unsubmitted: Boolean,
        recordedOn: LocalDate,
    ) = SubmissionDeadline.onConfirmFiling(due, listOf(apr20), recordedOn, unsubmitted)

    @Test
    fun `INV-AC-141 no filing fact gives the filing due date`() {
        SubmissionDeadline.of(due, emptyList()) shouldBe DueDate(due, FILING_DUE_DATE, false)
    }

    @Test
    fun `INV-AC-96 a filing fact before the filing due date is the due date`() {
        SubmissionDeadline.of(due, listOf(apr20)) shouldBe DueDate(apr20, FILING_FACT, false)
    }

    @Test
    fun `INV-AC-97 late filing extends nothing`() {
        SubmissionDeadline.of(due, listOf(LocalDate.of(2026, 5, 15))) shouldBe DueDate(due, FILING_DUE_DATE, false)
        SubmissionDeadline.of(due, listOf(LocalDate.of(2026, 5, 1))) shouldBe DueDate(due, FILING_DUE_DATE, false)
    }

    @Test
    fun `INV-AC-142 confirmFiling raises SUB-010 now only for an unsubmitted document`() {
        val late = confirm(true, LocalDate.of(2026, 4, 21))
        late.dueDate shouldBe DueDate(apr20, FILING_FACT, false)
        late.raiseSub010Now shouldBe true
        confirm(false, LocalDate.of(2026, 4, 21)).raiseSub010Now shouldBe false
    }

    @Test
    fun `INV-AC-142 recordedOn equal to the new due date does not raise and one day after does`() {
        confirm(true, apr20).raiseSub010Now shouldBe false
        confirm(true, apr20.plusDays(1)).raiseSub010Now shouldBe true
    }

    @Test
    fun `INV-AC-96 a fact equal to the filing due date has source FILING_DUE_DATE`() {
        SubmissionDeadline.of(due, listOf(due)) shouldBe DueDate(due, FILING_DUE_DATE, false)
    }

    @Test
    fun `INV-AC-96 two different facts give the earlier date and factsDisagree`() {
        SubmissionDeadline.of(due, listOf(apr22, apr20)) shouldBe DueDate(apr20, FILING_FACT, true)
        SubmissionDeadline.of(due, listOf(apr20, apr22)) shouldBe DueDate(apr20, FILING_FACT, true)
    }

    @Test
    fun `INV-AC-96 two equal facts do not disagree`() {
        SubmissionDeadline.of(due, listOf(apr20, apr20)) shouldBe DueDate(apr20, FILING_FACT, false)
    }

    @Test
    fun `INV-AC-96 a fact before the period end is accepted without check`() {
        val mar31 = LocalDate.of(2026, 3, 31)
        SubmissionDeadline.of(due, listOf(mar31)) shouldBe DueDate(mar31, FILING_FACT, false)
    }

    @Test
    fun `INV-AC-141 a leap-day filing due date is returned unchanged`() {
        val leap = LocalDate.of(2028, 2, 29)
        SubmissionDeadline.of(leap, emptyList()) shouldBe DueDate(leap, FILING_DUE_DATE, false)
    }
}
