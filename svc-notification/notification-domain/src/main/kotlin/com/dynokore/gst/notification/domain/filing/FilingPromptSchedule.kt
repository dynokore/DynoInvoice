package com.dynokore.gst.notification.domain.filing

import java.time.LocalDate

/** Prompts [send] now (at most one) and the due ones it [superseded]. */
data class PromptsDue(
    val send: PromptOn?,
    val superseded: List<PromptOn>,
)

/** INV-REQ-1277 / INV-REQ-918: the dates on which the filer of record is prompted. Pure: reads only its arguments. */
object FilingPromptSchedule {
    private const val DEFAULT_DAYS = 3

    /** Prompts in ascending date order, no repeated date. Throws [InvalidCustomDays] for a bad CUSTOM_DAYS list. */
    fun plan(
        option: PromptOption,
        customDays: List<Int>,
        firstReadyOn: LocalDate,
        dueDate: LocalDate,
    ): List<PromptOn> {
        val days =
            when (option) {
                PromptOption.READY_AND_3_DAYS -> listOf(DEFAULT_DAYS)
                PromptOption.CUSTOM_DAYS -> validated(customDays)
            }
        val ready = PromptOn(firstReadyOn, PromptKind.PERIOD_READY, null)
        val before =
            days
                .map { PromptOn(dueDate.minusDays(it.toLong()), PromptKind.DAYS_BEFORE_DUE, it) }
                .filter { it.date.isAfter(firstReadyOn) }
        return (listOf(ready) + before).sortedBy { it.date }
    }

    private fun validated(days: List<Int>): List<Int> {
        val reason =
            when {
                days.isEmpty() -> InvalidReason.EMPTY
                days.size > MAX_ITEMS -> InvalidReason.TOO_MANY
                days.toSet().size != days.size -> InvalidReason.DUPLICATE
                days.any { it < MIN_DAY || it > MAX_DAY } -> InvalidReason.OUT_OF_RANGE
                else -> null
            }
        if (reason != null) throw InvalidCustomDays(reason)
        return days
    }

    /**
     * The prompt to send today (the latest due one) and those it supersedes; none once the fact is recorded.
     * A prompt dated on or before the latest sent one counts as handled (INV-AC-10443: with the 27th sent, the
     * unsent 20th is not sent again).
     */
    fun promptsDue(
        plan: List<PromptOn>,
        today: LocalDate,
        filingFactRecorded: Boolean,
        sent: Set<PromptOn>,
    ): PromptsDue {
        if (filingFactRecorded) return PromptsDue(null, emptyList())
        val handledUpTo = sent.maxOfOrNull { it.date }
        val due =
            plan
                .filter { !it.date.isAfter(today) && it !in sent }
                .filter { handledUpTo == null || it.date.isAfter(handledUpTo) }
                .sortedBy { it.date }
        return PromptsDue(due.lastOrNull(), due.dropLast(1))
    }
}
