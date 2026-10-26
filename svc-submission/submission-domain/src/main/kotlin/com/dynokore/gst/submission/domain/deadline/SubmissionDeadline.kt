package com.dynokore.gst.submission.domain.deadline

import java.time.LocalDate

/** Which operand of the MIN gave the due date (INV-REQ-69). */
enum class DueSource { FILING_DUE_DATE, FILING_FACT }

/**
 * @property factsDisagree true when the recorded facts hold two different dates; the caller raises PER-010.
 */
data class DueDate(
    val date: LocalDate,
    val source: DueSource,
    val factsDisagree: Boolean,
)

/** @property raiseSub010Now true when a still-unsubmitted document is already late on the recording day. */
data class ConfirmResult(
    val dueDate: DueDate,
    val raiseSub010Now: Boolean,
)

/**
 * The invoice data due date: the earlier of the filing due date and the earliest recorded filing fact
 * (INV-REQ-69, INV-BR-94). Late filing extends nothing (INV-REQ-70). Pure: no clock, no state.
 */
object SubmissionDeadline {
    /**
     * @param filingDueDate the filing due date after the calendar rule and any recorded extension.
     * @param filedOn the dates of the recorded filing facts of the original return, possibly empty.
     */
    fun of(
        filingDueDate: LocalDate,
        filedOn: List<LocalDate>,
    ): DueDate {
        val earliest = filedOn.minOrNull()
        val disagree = filedOn.toSet().size > 1
        return if (earliest != null && earliest.isBefore(filingDueDate)) {
            DueDate(earliest, DueSource.FILING_FACT, disagree)
        } else {
            DueDate(filingDueDate, DueSource.FILING_DUE_DATE, disagree)
        }
    }

    /** SUB-010 is raised at once when the document is unsubmitted and [recordedOn] is after the new due date. */
    fun onConfirmFiling(
        filingDueDate: LocalDate,
        filedOn: List<LocalDate>,
        recordedOn: LocalDate,
        unsubmitted: Boolean,
    ): ConfirmResult {
        val due = of(filingDueDate, filedOn)
        return ConfirmResult(due, unsubmitted && recordedOn.isAfter(due.date))
    }
}
