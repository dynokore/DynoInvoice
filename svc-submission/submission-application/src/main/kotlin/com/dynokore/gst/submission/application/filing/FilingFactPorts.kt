package com.dynokore.gst.submission.application.filing

import java.time.Instant
import java.time.LocalDate

/** A filing fact handed to the store; the store stamps `recordedAt`, the use case never does. */
data class NewFilingFact(
    val entityRef: String,
    val periodRef: String,
    val filedAt: LocalDate,
    val acknowledgementRef: String,
    val portalRef: String?,
    val correction: Boolean,
    val recordedBy: String,
)

/** A stored filing fact; [recordedAt] was set by the store adapter. */
data class FilingFact(
    val entityRef: String,
    val periodRef: String,
    val filedAt: LocalDate,
    val acknowledgementRef: String,
    val portalRef: String?,
    val correction: Boolean,
    val recordedBy: String,
    val recordedAt: Instant,
)

/**
 * The append-only filing facts of the caller's tenant. The append runs in the caller's tenant transaction, is
 * serialised per (entity, period), and an identical fact is appended once (NIST SP 800-53 AU-9).
 */
interface FilingFactStore {
    /** The facts of the period in `seq` order. */
    fun facts(
        entityRef: String,
        periodRef: String,
    ): List<FilingFact>

    fun append(fact: NewFilingFact)
}

/** Thrown by [FilingDueDates] when more than one due date exists for the period. */
class AmbiguousDueDateException(
    message: String,
) : RuntimeException(message)

/** The due date of a period. */
fun interface FilingDueDates {
    /** Null when there is none; throws [AmbiguousDueDateException] when there is more than one. */
    fun dueDateOf(
        entityRef: String,
        periodRef: String,
    ): LocalDate?
}
