package com.dynokore.gst.payload.domain.regeneration

import java.time.LocalDate

enum class CorpusDocumentFunction { CREDIT_NOTE, OTHER }

/** One archived payload of the regeneration corpus with the pack versions recorded when it was issued. */
data class CorpusEntry(
    val id: String,
    val function: CorpusDocumentFunction,
    val issueDate: LocalDate,
    val precedingIssueDate: LocalDate?,
    val packVersions: Map<String, String>,
)

enum class CoverageGap { EMPTY_CORPUS, NO_RATE_CHANGE_KNOWN, NO_STRADDLING_CREDIT_NOTE }

sealed interface CorpusCoverage {
    data class Covered(
        val entryIds: List<String>,
    ) : CorpusCoverage

    data class NotCovered(
        val gap: CoverageGap,
    ) : CorpusCoverage
}

/**
 * The corpus obligation of the regeneration invariant (INV-REQ-704): the corpus holds a credit note whose
 * preceding document predates a rate change, pure. A rate change takes effect on its date.
 */
object RegenerationCorpusCoverage {
    fun check(
        entries: List<CorpusEntry>,
        rateChangeDates: Set<LocalDate>,
    ): CorpusCoverage {
        if (entries.isEmpty()) return CorpusCoverage.NotCovered(CoverageGap.EMPTY_CORPUS)
        return checkDates(entries, rateChangeDates)
    }

    private fun checkDates(
        entries: List<CorpusEntry>,
        rateChangeDates: Set<LocalDate>,
    ): CorpusCoverage {
        if (rateChangeDates.isEmpty()) return CorpusCoverage.NotCovered(CoverageGap.NO_RATE_CHANGE_KNOWN)
        val straddling = entries.filter { straddles(it, rateChangeDates) }.map { it.id }
        return straddling.takeIf { it.isNotEmpty() }?.let { CorpusCoverage.Covered(it) }
            ?: CorpusCoverage.NotCovered(CoverageGap.NO_STRADDLING_CREDIT_NOTE)
    }

    private fun straddles(
        entry: CorpusEntry,
        rateChangeDates: Set<LocalDate>,
    ): Boolean {
        val preceding = entry.precedingIssueDate ?: return false
        return entry.function == CorpusDocumentFunction.CREDIT_NOTE &&
            entry.packVersions.isNotEmpty() &&
            rateChangeDates.any { d -> preceding < d && d <= entry.issueDate }
    }
}
