package com.dynokore.platform.calendarandperiods

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// Deadline mode (INV-REQ-1019, INV-AC-15749, INV-Q-15741): pure functions that return facts. The window in days is the
// configuration of the variation point INV-REQ-1280, so it is an argument and the library holds no default or bound.

/** True when [today] is within [windowDays] of [dueDate] or past it, so an overdue period stays in deadline mode. */
fun inDeadlineMode(
    dueDate: LocalDate,
    today: LocalDate,
    windowDays: Int,
): Boolean {
    require(windowDays >= 1) { "windowDays must be at least 1: $windowDays" }
    return noticeDue(dueDate, today, windowDays)
}

/** A period and its filing due date. */
data class PeriodDue(
    val periodRef: String,
    val dueDate: LocalDate,
)

/**
 * The [PeriodDue.periodRef] values with the periods in deadline mode first; each group is ascending by due date, then
 * by period reference in text order. The period references must be distinct.
 */
fun deadlineOrder(
    periods: List<PeriodDue>,
    today: LocalDate,
    windowDays: Int,
): List<String> {
    require(windowDays >= 1) { "windowDays must be at least 1: $windowDays" }
    val refs = periods.map { it.periodRef }
    require(refs.toSet().size == refs.size) { "periodRef values must be distinct: $refs" }
    return periods
        .sortedWith(
            compareBy<PeriodDue> { !inDeadlineMode(it.dueDate, today, windowDays) }
                .thenBy { it.dueDate }
                .thenBy { it.periodRef },
        ).map { it.periodRef }
}

/** The whole hours from a point in time to the filing gate, and whether the gate has been reached. */
data class HoursToGate(
    val hours: Long,
    val gatePassed: Boolean,
)

/** Whole hours from [at] to [gate], rounded down; [gate] not after [at] gives `HoursToGate(0, true)`. */
fun hoursToGate(
    gate: Instant,
    at: Instant,
): HoursToGate {
    if (!gate.isAfter(at)) return HoursToGate(0, true)
    return HoursToGate(Duration.between(at, gate).toHours(), false)
}
