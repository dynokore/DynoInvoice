package com.dynokore.platform.calendarandperiods

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A set of period records in which no two share a day (both ends are inclusive, so a period that ends on the day
 * another starts overlaps it). An empty set is allowed and holds no date.
 */
class PeriodSet private constructor(
    val periods: List<PeriodRecord>,
) {
    /** The period that holds [date], or null when none does. */
    fun holding(date: LocalDate): PeriodRecord? = periods.firstOrNull { date in it }

    companion object {
        fun of(periods: List<PeriodRecord>): PeriodSet {
            val sorted = periods.sortedBy { it.start }
            sorted.zipWithNext().forEach { (earlier, later) ->
                require(earlier.end.isBefore(later.start)) {
                    "periods ${earlier.start}..${earlier.end} and ${later.start}..${later.end} overlap"
                }
            }
            return PeriodSet(sorted)
        }
    }
}

/** A transaction timestamp: either with its own zone, or without one (INV-REQ-77). */
sealed interface TransactionTimestamp {
    /** A timestamp that carries its own zone. */
    data class Explicit(
        val value: ZonedDateTime,
    ) : TransactionTimestamp

    /** A timestamp without a zone; the caller's source zone is assumed and the assumption is traced (INV-AC-199). */
    data class ZoneLess(
        val value: LocalDateTime,
    ) : TransactionTimestamp
}

/** The outcome of [assignPeriod]. [trace] is ordered text naming the inputs and the assumptions made. */
sealed interface PeriodAssignment {
    val boundaryDate: LocalDate
    val trace: List<String>

    /** The period that holds the boundary date. */
    data class Assigned(
        val period: PeriodRecord,
        override val boundaryDate: LocalDate,
        override val trace: List<String>,
    ) : PeriodAssignment

    /** No period holds the boundary date. The nearest period is never chosen and nothing is defaulted. */
    data class NoPeriod(
        override val boundaryDate: LocalDate,
        override val trace: List<String>,
    ) : PeriodAssignment
}

/**
 * Assigns the period that holds the transaction date (INV-REQ-76, INV-REQ-77). The boundary date is the calendar date
 * of the instant in [boundaryZone]; a [TransactionTimestamp.ZoneLess] value is first read in [sourceZone]. Both zones
 * are arguments: the library holds no zone constant and reads no clock, so the result depends on the arguments only
 * (INV-AC-118).
 */
fun assignPeriod(
    periods: PeriodSet,
    timestamp: TransactionTimestamp,
    boundaryZone: ZoneId,
    sourceZone: ZoneId,
): PeriodAssignment {
    val trace = mutableListOf<String>()
    val instant: ZonedDateTime =
        when (timestamp) {
            is TransactionTimestamp.Explicit -> {
                trace += "input timestamp ${timestamp.value}"
                timestamp.value
            }

            is TransactionTimestamp.ZoneLess -> {
                trace += "input timestamp ${timestamp.value} without a zone"
                trace += "assumed zone $sourceZone for a timestamp without a zone"
                timestamp.value.atZone(sourceZone)
            }
        }
    val boundaryDate = instant.withZoneSameInstant(boundaryZone).toLocalDate()
    trace += "boundary date $boundaryDate in zone $boundaryZone"
    val period = periods.holding(boundaryDate)
    return if (period != null) {
        PeriodAssignment.Assigned(period, boundaryDate, trace)
    } else {
        trace += "no period holds $boundaryDate"
        PeriodAssignment.NoPeriod(boundaryDate, trace)
    }
}
