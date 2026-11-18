package com.dynokore.gst.evidence.domain.retention

import java.time.LocalDate
import java.util.Locale

/**
 * The period_ref of an imported prior-evidence record: the calendar quarter of its transactionDate written as
 * YYYY-Qn (owner ruling 2026-11-18, option A; 2026-01-15 gives 2026-Q1). A pure function of its input: no clock,
 * no I/O, no framework.
 */
object PriorEvidencePeriodRef {
    private const val MIN_YEAR = 1
    private const val MAX_YEAR = 9999

    fun of(transactionDate: LocalDate): String {
        val year = transactionDate.year
        require(year in MIN_YEAR..MAX_YEAR) { "transactionDate year $year is outside $MIN_YEAR..$MAX_YEAR" }
        val quarter = (transactionDate.monthValue - 1) / 3 + 1
        return String.format(Locale.ROOT, "%04d-Q%d", year, quarter)
    }
}
