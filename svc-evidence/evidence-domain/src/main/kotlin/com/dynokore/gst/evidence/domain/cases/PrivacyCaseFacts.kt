package com.dynokore.gst.evidence.domain.cases

import java.time.Instant
import java.time.LocalDate

/** The kind of an individual's request; the members equal `requestKind` of `OpenPrivacyCaseRequest`. */
enum class RequestKind { ACCESS, CORRECTION }

/** How a privacy case was closed; the members equal `outcome` of `ClosePrivacyCaseRequest`. */
enum class CaseOutcome { ANSWERED, REFUSED }

/** The closure of a privacy case (`ClosePrivacyCaseRequest`). */
data class CaseClosure(
    val closedAt: Instant,
    val outcome: CaseOutcome,
    val basis: String,
) {
    init {
        require(basis.length in 1..MAX_BASIS) { "basis must hold 1 to $MAX_BASIS characters" }
    }

    companion object {
        const val MAX_BASIS = 500
    }
}

/**
 * The facts of a privacy case. The deadline is computed once at opening and kept; [noticePromptedAt] and [closure]
 * may both be set, because a case may be prompted and later closed.
 */
data class PrivacyCaseFacts(
    val requestKind: RequestKind,
    val requestReference: String,
    val receivedOn: LocalDate,
    val responseDeadline: LocalDate,
    val deadlinePeriodDays: Int,
    val closure: CaseClosure?,
    val noticePromptedAt: Instant?,
) {
    init {
        require(requestReference.length in 1..MAX_REQUEST_REFERENCE) {
            "requestReference must hold 1 to $MAX_REQUEST_REFERENCE characters"
        }
        require(deadlinePeriodDays >= 1) { "deadlinePeriodDays must be at least 1" }
        require(!responseDeadline.isBefore(receivedOn)) { "responseDeadline is before receivedOn" }
    }

    companion object {
        const val MAX_REQUEST_REFERENCE = 100
    }
}
