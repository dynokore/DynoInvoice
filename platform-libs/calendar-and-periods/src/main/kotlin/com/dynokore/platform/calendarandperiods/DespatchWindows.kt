package com.dynokore.platform.calendarandperiods

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Despatch windows (INV-REQ-169, INV-REQ-73, INV-REQ-1281, INV-REQ-1296): pure functions that return facts; the
// embedding service decides. Every input is an argument and the library reads no time source.

private const val HOURS_PER_DAY = 24L
private const val HOURS_PER_WEEK = 168L
private const val MAX_RUNS_PER_DAY = 24

/** The despatch (or extraction) window of a setting: continuous, a daily batch or a weekly batch. */
enum class BatchWindow(
    val interval: Duration,
) {
    CONTINUOUS(Duration.ZERO),
    DAILY(Duration.ofHours(HOURS_PER_DAY)),
    WEEKLY(Duration.ofHours(HOURS_PER_WEEK)),
}

/** The interval of an extraction run [runsPerDay] times a day: 24 hours divided by the runs; 2 to 24 runs only. */
fun severalDailyInterval(runsPerDay: Int): Duration {
    require(runsPerDay in 2..MAX_RUNS_PER_DAY) { "runsPerDay must be 2 to 24: $runsPerDay" }
    return Duration.ofHours(HOURS_PER_DAY).dividedBy(runsPerDay.toLong())
}

/**
 * The first opening of [window] at or after [after], in the zone of [after]: 00:00 for a daily window, Monday 00:00 for
 * a weekly one (an opening equal to [after] counts). Null for a continuous window, which has no opening.
 */
fun nextWindowOpening(
    after: ZonedDateTime,
    window: BatchWindow,
): Instant? {
    val step = if (window == BatchWindow.DAILY) 1L else 7L

    fun firstAtOrAfter(startDay: LocalDate): Instant {
        val opening = startDay.atStartOfDay(after.zone).toInstant()
        if (opening >= after.toInstant()) return opening
        return startDay.plusDays(step).atStartOfDay(after.zone).toInstant()
    }

    return when (window) {
        BatchWindow.CONTINUOUS -> null
        BatchWindow.DAILY -> firstAtOrAfter(after.toLocalDate())
        BatchWindow.WEEKLY -> firstAtOrAfter(after.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)))
    }
}

/** Why a document goes when it does. */
enum class DespatchReason { IMMEDIATE_CONTINUOUS, IMMEDIATE_PAST_HARD_STOP, IMMEDIATE_MARGIN, WINDOW }

/** When a document is despatched; [pastHardStop] is true exactly when it was ready after the hard stop. */
data class Despatch(
    val at: Instant,
    val reason: DespatchReason,
    val pastHardStop: Boolean,
)

/**
 * Despatch time of a document ready at [readyAt], given the [nextOpening] of its window (null when continuous) and the
 * [hardStop] (due minus the safety margin). A document whose hard stop falls before the next opening goes at once.
 */
fun despatchAt(
    readyAt: Instant,
    nextOpening: Instant?,
    hardStop: Instant,
): Despatch {
    require(nextOpening == null || !nextOpening.isBefore(readyAt)) {
        "nextOpening ($nextOpening) must not be before readyAt ($readyAt)"
    }
    val past = readyAt.isAfter(hardStop)
    return when {
        nextOpening == null -> Despatch(readyAt, DespatchReason.IMMEDIATE_CONTINUOUS, past)
        past -> Despatch(readyAt, DespatchReason.IMMEDIATE_PAST_HARD_STOP, true)
        hardStop.isBefore(nextOpening) -> Despatch(readyAt, DespatchReason.IMMEDIATE_MARGIN, false)
        else -> Despatch(nextOpening, DespatchReason.WINDOW, false)
    }
}

/**
 * True when the extraction and despatch intervals together fit in the shortest gap minus the safety margin (the margin
 * is the only buffer). A negative argument is refused.
 */
fun weeklyWindowFits(
    extractionInterval: Duration,
    despatchInterval: Duration,
    shortestGap: Duration,
    margin: Duration,
): Boolean {
    require(!extractionInterval.isNegative) { "extractionInterval must not be negative: $extractionInterval" }
    require(!despatchInterval.isNegative) { "despatchInterval must not be negative: $despatchInterval" }
    require(!shortestGap.isNegative) { "shortestGap must not be negative: $shortestGap" }
    require(!margin.isNegative) { "margin must not be negative: $margin" }
    return extractionInterval.plus(despatchInterval) <= shortestGap.minus(margin)
}

/**
 * The least number of whole calendar days, over the windows of [frequency], from the window end to the due date of its
 * return form before any non-business-day move. No window of the frequency is a coverage failure.
 */
fun shortestGap(
    pack: CalendarPack,
    frequency: FilingFrequency,
): Duration {
    val windows = pack.windowsOf(frequency)
    if (windows.isEmpty()) {
        throw CalendarPackCoverageException(
            "calendar pack ${pack.version} has no window of frequency ${frequency.code}",
        )
    }
    val days =
        windows.minOf { w ->
            ChronoUnit.DAYS.between(w.end, pack.dueRuleFor(w.returnForm).applyTo(w.end))
        }
    return Duration.ofDays(days)
}
