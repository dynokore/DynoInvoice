package com.dynokore.gst.canonical.domain.document

import java.time.LocalDate
import java.util.UUID

/** A stored document that carries the number a credit note names as its preceding document. */
data class PrecedingCandidate(
    val docUuid: UUID,
    val transactionDate: LocalDate,
)

/** The outcome of the link from a credit note to its preceding document (INV-REQ-709, INV-Q-6311 option A). */
sealed interface PrecedingResolution {
    data class Resolved(
        val docUuid: UUID,
    ) : PrecedingResolution

    data object Unresolved : PrecedingResolution
}

/**
 * Picks the preceding document among the candidates of one number. With a preceding date only the candidates of
 * exactly that transaction date remain; exactly one remaining candidate is the link, none or several give no link:
 * the selector never guesses between two documents (INV-REQ-690, INV-REQ-1208, INV-Q-6311).
 */
object PrecedingDocumentSelector {
    fun select(
        candidates: List<PrecedingCandidate>,
        precedingDocumentDate: LocalDate?,
    ): PrecedingResolution {
        val remaining =
            if (precedingDocumentDate == null) {
                candidates
            } else {
                candidates.filter { it.transactionDate == precedingDocumentDate }
            }
        val only = remaining.singleOrNull() ?: return PrecedingResolution.Unresolved
        return PrecedingResolution.Resolved(only.docUuid)
    }
}
