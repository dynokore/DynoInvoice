package com.dynokore.gst.aggregation.domain

import java.time.LocalDate

/** A prescribed period, both ends included. */
data class PeriodSpan(
    val start: LocalDate,
    val end: LocalDate,
    val periodRef: String,
) {
    init {
        require(!end.isBefore(start)) { "a span ends on or after its start" }
    }

    fun covers(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** The part of a window inside one prescribed period (INV-REQ-435 item 2). */
data class WindowPart(
    val start: LocalDate,
    val end: LocalDate,
    val periodRef: String,
)

sealed interface SplitResult {
    /** The parts in date order, each inside one span. */
    data class Split(
        val parts: List<WindowPart>,
    ) : SplitResult

    /** A date of the window that no span covers: no part is returned, never a guess. */
    data class Uncovered(
        val firstDate: LocalDate,
    ) : SplitResult
}

/** Splits a window at the ends of the prescribed periods (INV-REQ-435 item 1). */
object PeriodSplit {
    fun split(
        window: AggregationWindow,
        spans: List<PeriodSpan>,
    ): SplitResult {
        val ordered = spans.sortedBy { it.start }
        val parts = mutableListOf<WindowPart>()
        var cursor = window.start
        while (!cursor.isAfter(window.end)) {
            val date = cursor
            val span = ordered.firstOrNull { it.covers(date) } ?: return SplitResult.Uncovered(date)
            val partEnd = if (span.end.isBefore(window.end)) span.end else window.end
            parts += WindowPart(cursor, partEnd, span.periodRef)
            cursor = partEnd.plusDays(1)
        }
        return SplitResult.Split(parts)
    }
}
