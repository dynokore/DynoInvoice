package com.dynokore.gst.aggregation.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * How often a window closes (INV-REQ-413 item 1, INV-REQ-1316). POS and STI default to daily and PCP to monthly;
 * weekly is the option of every category. Monthly is not an option of POS and STI, and daily is not one of PCP.
 */
enum class Cadence {
    DAILY,
    WEEKLY,
    MONTHLY,
    ;

    companion object {
        fun defaultFor(type: AggregateType): Cadence =
            when (type) {
                AggregateType.POS, AggregateType.STI -> DAILY
                AggregateType.PCP -> MONTHLY
            }

        fun allowedFor(type: AggregateType): Set<Cadence> =
            when (type) {
                AggregateType.POS, AggregateType.STI -> setOf(DAILY, WEEKLY)
                AggregateType.PCP -> setOf(MONTHLY, WEEKLY)
            }
    }
}

/** The state of a window; the three names equal the contract enum `windowState`. */
enum class WindowState {
    OPEN,
    CLOSED,
    LATE,
}

/** A window of days, both ends included. */
data class AggregationWindow(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!end.isBefore(start)) { "a window ends on or after its start" }
    }

    companion object {
        private const val DAYS_MONDAY_TO_SUNDAY = 6L

        /** The window of [cadence] holding [date]: the day, the ISO week (Monday to Sunday) or the calendar month. */
        fun containing(
            cadence: Cadence,
            date: LocalDate,
        ): AggregationWindow =
            when (cadence) {
                Cadence.DAILY -> {
                    AggregationWindow(date, date)
                }

                Cadence.WEEKLY -> {
                    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    AggregationWindow(monday, monday.plusDays(DAYS_MONDAY_TO_SUNDAY))
                }

                Cadence.MONTHLY -> {
                    AggregationWindow(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
                }
            }
    }
}
