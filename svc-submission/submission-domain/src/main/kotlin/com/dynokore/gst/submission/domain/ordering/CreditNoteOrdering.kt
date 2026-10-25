package com.dynokore.gst.submission.domain.ordering

import java.time.Instant

/** The lifecycle states of lifecycle-states.schema.json: 4 document-level and 14 version-level (INV-REQ-709). */
enum class LifecycleState {
    INGESTED,
    HELD,
    BLOCKED,
    NOT_SUBMITTABLE,
    READY,
    AWAITING_PRECEDING,
    QUEUED,
    SENT,
    SENT_UNCONFIRMED,
    RETRY,
    DEAD_LETTERED,
    REJECTED,
    CANCELLED,
    ACKNOWLEDGED,
    SUPERSEDED,
    NETWORK_ISSUED,
    EXTERNALLY_SUBMITTED,
    WITHDRAWN,
}

/** The VP-59 option of the entity: what happens to a credit note whose original cannot go first in time. */
enum class CreditNoteOption { RELEASE_ALONE, HOLD_FOR_APPROVER }

/** How a single state counts for the preceding document (INV-REQ-709, INV-REQ-559 by analogy). */
enum class StateClass { AT_IRAS, NOT_GOING, PENDING }

/**
 * @property precedingDocUuid the credit note's preceding-document link, absent when it has none.
 * @property preceding the version states of the preceding document in version order, or null when the register has
 *   no entry for it.
 * @property releaseAloneAt the credit note's due date minus the safety margin, computed by the caller.
 */
data class CreditNoteOrderingInput(
    val precedingDocUuid: String?,
    val preceding: List<LifecycleState>?,
    val option: CreditNoteOption,
    val now: Instant,
    val releaseAloneAt: Instant,
)

enum class ReleaseReason { NO_PRECEDING_LINK, PRECEDING_AT_IRAS, PRECEDING_NOT_GOING }

sealed interface CreditNoteDecision {
    data class Release(
        val reason: ReleaseReason,
    ) : CreditNoteDecision

    data class Hold(
        val code: String = SUB_011,
    ) : CreditNoteDecision

    data class ReleaseAlone(
        val code: String = SUB_012,
        val bothInReadiness: Boolean = true,
    ) : CreditNoteDecision

    data class HoldPastMargin(
        val code: String = SUB_012,
        val bothInReadiness: Boolean = true,
    ) : CreditNoteDecision

    companion object {
        const val SUB_011 = "SUB-011"
        const val SUB_012 = "SUB-012"
    }
}

/** Decides the hold, release and release-alone of a credit note against its preceding document. Pure. */
object CreditNoteOrdering {
    fun classify(state: LifecycleState): StateClass =
        when (state) {
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.EXTERNALLY_SUBMITTED,
            LifecycleState.SUPERSEDED,
            -> StateClass.AT_IRAS

            LifecycleState.NOT_SUBMITTABLE,
            LifecycleState.CANCELLED,
            LifecycleState.WITHDRAWN,
            -> StateClass.NOT_GOING

            LifecycleState.INGESTED,
            LifecycleState.HELD,
            LifecycleState.BLOCKED,
            LifecycleState.READY,
            LifecycleState.AWAITING_PRECEDING,
            LifecycleState.QUEUED,
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.DEAD_LETTERED,
            LifecycleState.REJECTED,
            LifecycleState.NETWORK_ISSUED,
            -> StateClass.PENDING
        }

    /** At IRAS when any version is; not going when the latest is and none is; otherwise pending (fails closed). */
    fun classify(versions: List<LifecycleState>?): StateClass {
        val classes = versions.orEmpty().map { classify(it) }
        return when {
            classes.any { it == StateClass.AT_IRAS } -> StateClass.AT_IRAS
            classes.lastOrNull() == StateClass.NOT_GOING -> StateClass.NOT_GOING
            else -> StateClass.PENDING
        }
    }

    fun decide(input: CreditNoteOrderingInput): CreditNoteDecision {
        if (input.precedingDocUuid == null) {
            return CreditNoteDecision.Release(ReleaseReason.NO_PRECEDING_LINK)
        }
        return when (classify(input.preceding)) {
            StateClass.AT_IRAS -> {
                CreditNoteDecision.Release(ReleaseReason.PRECEDING_AT_IRAS)
            }

            StateClass.NOT_GOING -> {
                CreditNoteDecision.Release(ReleaseReason.PRECEDING_NOT_GOING)
            }

            StateClass.PENDING -> {
                when {
                    input.now < input.releaseAloneAt -> CreditNoteDecision.Hold()
                    input.option == CreditNoteOption.RELEASE_ALONE -> CreditNoteDecision.ReleaseAlone()
                    else -> CreditNoteDecision.HoldPastMargin()
                }
            }
        }
    }
}
