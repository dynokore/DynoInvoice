package com.dynokore.gst.submission.domain.filing

import java.time.LocalDate

/** Error code raised when two filing facts of the original return carry different dates (INV-REQ-917). */
const val CONFLICT_CODE = "PER-010"

/** One filing fact of a period, reduced to what decides the governing date. */
data class FiledFact(
    val filedAt: LocalDate,
    val correction: Boolean,
)

/** The other date of a conflict (PER-010). */
data class FilingConflict(
    val otherFiledAt: LocalDate,
    val code: String = CONFLICT_CODE,
) {
    init {
        require(code == CONFLICT_CODE) { "A filing conflict carries $CONFLICT_CODE" }
    }
}

/** The date that governs the period and the conflict that remains, if any. */
data class Governing(
    val governingFiledAt: LocalDate,
    val conflict: FilingConflict?,
)

/**
 * Derives the governing filing date of one period from its facts (INV-REQ-917, variation point INV-REQ-1274 default).
 * The latest correction governs and leaves no conflict; otherwise the earliest date of the original facts governs and
 * a conflict exists exactly when two distinct dates exist. Pure; no clock.
 */
object GoverningFiling {
    /** [facts] are in `seq` order; null when there is none. */
    fun of(facts: List<FiledFact>): Governing? {
        val latestCorrection = facts.lastOrNull { it.correction }
        val earliest = facts.minOfOrNull { it.filedAt }
        return when {
            earliest == null -> {
                null
            }

            latestCorrection != null -> {
                Governing(latestCorrection.filedAt, null)
            }

            else -> {
                val other = facts.map { it.filedAt }.filter { it.isAfter(earliest) }.minOrNull()
                Governing(earliest, other?.let { FilingConflict(it) })
            }
        }
    }
}
