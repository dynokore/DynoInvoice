package com.dynokore.gst.submission.domain.boundary

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.util.UUID

enum class ReversibilityClass { UNDOABLE, COMPENSABLE, IRREVERSIBLE }

sealed interface BulkResolveVerdict {
    data object Allowed : BulkResolveVerdict {
        val reversibility: ReversibilityClass = ReversibilityClass.UNDOABLE
    }

    data class Refused(
        val submittedCount: Int,
    ) : BulkResolveVerdict {
        val correctionPath: ReversibilityClass = SubmissionBoundary.CORRECTION_PATH
    }
}

/**
 * INV-REQ-959, INV-REQ-965, INV-REQ-1005 (INV-Q-6216): the one named set of states from which a document counts as
 * submitted, and the decisions derived from it. Pure: no clock, file or database; arguments are never changed.
 */
object SubmissionBoundary {
    val SUBMITTED_STATES: Set<LifecycleState> =
        setOf(
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.DEAD_LETTERED,
            LifecycleState.REJECTED,
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SUPERSEDED,
            LifecycleState.NETWORK_ISSUED,
            LifecycleState.EXTERNALLY_SUBMITTED,
        )

    /** After transmission a correction is a new version under the same Doc UUID or a credit note (INV-REQ-965). */
    val CORRECTION_PATH: ReversibilityClass = ReversibilityClass.COMPENSABLE

    fun isSubmitted(state: LifecycleState): Boolean = state in SUBMITTED_STATES

    /** Number of Doc UUIDs with at least one submitted version; no version record counts as not submitted. */
    fun submittedCount(versionStatesByDoc: Map<UUID, Collection<LifecycleState>>): Int =
        versionStatesByDoc.values.count { states -> states.any { isSubmitted(it) } }

    fun classOf(state: LifecycleState): ReversibilityClass =
        if (isSubmitted(state)) ReversibilityClass.IRREVERSIBLE else ReversibilityClass.UNDOABLE

    /** Refuses the whole group when any member is submitted; a negative count is a caller defect. */
    fun bulkResolveVerdict(submittedCount: Int): BulkResolveVerdict {
        require(submittedCount >= 0) { "submittedCount must not be negative: $submittedCount" }
        return if (submittedCount == 0) BulkResolveVerdict.Allowed else BulkResolveVerdict.Refused(submittedCount)
    }
}
