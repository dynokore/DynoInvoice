package com.dynokore.platform.calendarandperiods

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** A standard window of one filing frequency in the pack: explicit dates and the return form (INV-REQ-83). */
data class StandardWindow(
    val start: LocalDate,
    val end: LocalDate,
    val returnForm: String,
) {
    init {
        require(!end.isBefore(start)) { "a window ends on or after its start" }
        require(returnForm.isNotBlank()) { "a window has a return form" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** Whether a due date falling on a non-business day moves (VP-62, INV-REQ-87). A parameter, never a constant. */
enum class NonBusinessDayRule { NO_MOVE, NEXT_WORKING_DAY }

/**
 * How the pack cuts a period in which the filing frequency changes (INV-REQ-688). The pack chooses; the library holds
 * neither as a default.
 *  - [CUT_AT_CHANGE]: the transitional period ends the day before the change; the next period starts on the change date.
 *  - [EXTEND_TO_NEW_WINDOW]: the transitional period runs to the end of the new frequency's window that holds the
 *    change date.
 */
enum class TransitionStyle { CUT_AT_CHANGE, EXTEND_TO_NEW_WINDOW }

/** How a filing due date follows from a period end, as data (INV-REQ-87: pack data, not code). */
data class DueRule(
    val monthsAfterEnd: Int,
    /** When true the date lands on the last day of the month that [monthsAfterEnd] reaches. */
    val endOfMonth: Boolean,
    val daysAfterEnd: Int,
) {
    init {
        require(monthsAfterEnd >= 0 && daysAfterEnd >= 0) { "a due rule never goes back in time" }
    }

    fun applyTo(periodEnd: LocalDate): LocalDate {
        val month = YearMonth.from(periodEnd).plusMonths(monthsAfterEnd.toLong())
        val day = if (endOfMonth) month.atEndOfMonth() else month.atDay(minOf(periodEnd.dayOfMonth, month.lengthOfMonth()))
        return day.plusDays(daysAfterEnd.toLong())
    }
}

/** Weekend days and public holidays: calendar-pack data (INV-REQ-87 b2). */
class BusinessCalendar(
    val weekend: Set<DayOfWeek>,
    val holidays: Set<LocalDate>,
) {
    init {
        require(weekend.size < DayOfWeek.entries.size) { "at least one day of the week is a working day" }
    }

    fun isBusinessDay(date: LocalDate): Boolean = date.dayOfWeek !in weekend && date !in holidays

    fun nextBusinessDay(date: LocalDate): LocalDate {
        var day = date
        while (!isBusinessDay(day)) day = day.plusDays(1)
        return day
    }
}

/**
 * The calendar pack input of period generation and due-date derivation: every length, form, due rule, weekend and
 * holiday is data here. [pinnedRule] is set once IRAS answers INV-NOTE-4#row:Q-25: it then applies to every entity
 * and the per-entity rule has no effect (INV-REQ-87 b3).
 */
class CalendarPack(
    val version: String,
    windows: Map<FilingFrequency, List<StandardWindow>>,
    val finalReturnForm: String,
    val dueRules: Map<String, DueRule>,
    val calendar: BusinessCalendar,
    val transition: TransitionStyle,
    val pinnedRule: NonBusinessDayRule?,
) {
    private val windowsByFrequency: Map<FilingFrequency, List<StandardWindow>> =
        windows.mapValues { (_, list) ->
            val sorted = list.sortedBy { it.start }
            sorted.zipWithNext().forEach { (a, b) ->
                require(a.end.isBefore(b.start)) { "the windows of one frequency do not overlap" }
            }
            sorted
        }

    init {
        require(version.isNotBlank()) { "a calendar pack has a version" }
        require(finalReturnForm.isNotBlank()) { "a calendar pack names the final return form" }
    }

    /** The window of [frequency] that holds [date], or a coverage failure naming the gap. */
    fun windowContaining(
        frequency: FilingFrequency,
        date: LocalDate,
    ): StandardWindow =
        windowsByFrequency[frequency]?.firstOrNull { date in it }
            ?: throw CalendarPackCoverageException(
                "calendar pack $version has no window of frequency ${frequency.code} on $date",
            )

    fun dueRuleFor(returnForm: String): DueRule =
        dueRules[returnForm]
            ?: throw CalendarPackCoverageException("calendar pack $version has no due rule for return form $returnForm")

    /** The rule that applies: the pack's pinned one, else the entity's. */
    fun effectiveRule(entityRule: NonBusinessDayRule): NonBusinessDayRule = pinnedRule ?: entityRule
}

/** A filing due date and what the non-business-day rule did to it (INV-REQ-87). */
data class DerivedDueDate(
    val unadjusted: LocalDate,
    val adjusted: LocalDate,
    val rule: NonBusinessDayRule,
) {
    val moved: Boolean get() = adjusted != unadjusted
}

/** Derives a filing due date from a period end, a return form and the non-business-day rule. */
interface DueDates {
    fun derive(
        periodEnd: LocalDate,
        returnForm: String,
        entityRule: NonBusinessDayRule,
    ): DerivedDueDate
}
