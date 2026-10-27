package com.dynokore.gst.canonical.domain.dating

import java.time.LocalDate

/** A prescribed period; [start] and [end] are both inclusive. */
data class PeriodSpan(
    val periodRef: String,
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(!end.isBefore(start)) { "end $end is before start $start" }
    }

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** The outcome of [PeriodAssignment.assign]. */
sealed interface PeriodDecision {
    /** Exactly one span contains the date; [periodRef] is the span's reference exactly as given. */
    data class Assigned(
        val periodRef: String,
    ) : PeriodDecision

    /** No span contains the date (PER-009, auto-released when the period is generated). */
    data class Held(
        val code: HoldCode,
    ) : PeriodDecision

    /** Two or more spans contain the date; nothing is picked (SRT-010). */
    data class Inconsistent(
        val periodRefs: List<String>,
    ) : PeriodDecision {
        val failureCode: String get() = FAILURE_CODE

        companion object {
            const val FAILURE_CODE: String = "SRT-010"
        }
    }
}

/** Step 3 of INV-NOTE-3 §4.2.3: pure, no clock (INV-REQ-76). */
object PeriodAssignment {
    fun assign(
        date: LocalDate,
        spans: List<PeriodSpan>,
    ): PeriodDecision {
        val hits = spans.filter { it.contains(date) }
        return when (hits.size) {
            0 -> PeriodDecision.Held(HoldCode.PER_009)
            1 -> PeriodDecision.Assigned(hits.single().periodRef)
            else -> PeriodDecision.Inconsistent(hits.map { it.periodRef })
        }
    }
}
