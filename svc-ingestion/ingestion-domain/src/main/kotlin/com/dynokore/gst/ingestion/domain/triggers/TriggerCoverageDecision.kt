package com.dynokore.gst.ingestion.domain.triggers

import java.time.LocalDate

/** Pure coverage decisions (INV-REQ-788): validation of a record and the list of classes still uncovered. */
object TriggerCoverageDecision {
    /** A mapping; refuses a blank or over-40-character [adapterRef] with [IllegalArgumentException]. */
    fun mapped(adapterRef: String): CoverageRecord.Mapped = CoverageRecord.Mapped(adapterRef)

    /** A REVIEW_WAIVER; refuses a missing [reviewDate] with [IllegalArgumentException]. */
    fun waived(reviewDate: LocalDate?): CoverageRecord.Waived =
        CoverageRecord.Waived(requireNotNull(reviewDate) { "a waiver needs a review date" })

    /** A COVERED_BY_OTHER_SOLUTION record; refuses a blank member with [IllegalArgumentException]. */
    fun coveredElsewhere(
        coveringSolution: String,
        evidenceReference: String,
    ): CoverageRecord.CoveredElsewhere = CoverageRecord.CoveredElsewhere(coveringSolution, evidenceReference)

    /** The class for [name], or [IllegalArgumentException] when it is outside the five. */
    fun classOf(name: String): TriggerClass = requireNotNull(TriggerClass.parse(name)) { "unknown trigger class" }

    /** The classes without a record, in declaration order; empty means complete. */
    fun missing(records: Map<TriggerClass, CoverageRecord>): List<TriggerClass> =
        TriggerClass.entries.filter { it !in records }
}
