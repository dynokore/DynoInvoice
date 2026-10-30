package com.dynokore.gst.submission.domain.backfill

/**
 * An approval of a backfill reconciliation. [authorised] is the answer of `AuthorityReplica.decide` for the approving
 * user on the scoped permission key of the backfill approval, computed by the caller; no role name is received.
 */
data class BackfillApproval(
    val approverUserId: String,
    val requesterUserId: String,
    val authorised: Boolean,
    val approvedDocUuids: Set<String>,
)

enum class BackfillHoldReason { NO_APPROVAL, NOT_AUTHORISED, SAME_PERSON, NOTHING_TO_SUBMIT, STALE_APPROVAL }

/** Only [Transmit] carries documents; a [Hold] transmits nothing (INV-REQ-1090 behavior 3). */
sealed interface BackfillGateDecision {
    data class Hold(
        val reason: BackfillHoldReason,
    ) : BackfillGateDecision

    data class Transmit(
        val docUuids: List<String>,
    ) : BackfillGateDecision
}

/** Fails closed: deny unless an authorised, different-person approval covers exactly the reconciled set. Pure. */
object BackfillTransmissionGate {
    fun decide(
        reconciliation: BackfillReconciliation,
        approval: BackfillApproval?,
    ): BackfillGateDecision {
        if (approval == null) return BackfillGateDecision.Hold(BackfillHoldReason.NO_APPROVAL)
        require(approval.approverUserId.isNotBlank()) { "approverUserId must not be blank" }
        require(approval.requesterUserId.isNotBlank()) { "requesterUserId must not be blank" }
        return when {
            !approval.authorised -> {
                BackfillGateDecision.Hold(BackfillHoldReason.NOT_AUTHORISED)
            }

            approval.approverUserId == approval.requesterUserId -> {
                BackfillGateDecision.Hold(BackfillHoldReason.SAME_PERSON)
            }

            reconciliation.toBeSubmitted.isEmpty() -> {
                BackfillGateDecision.Hold(BackfillHoldReason.NOTHING_TO_SUBMIT)
            }

            approval.approvedDocUuids != reconciliation.toBeSubmitted.toSet() -> {
                BackfillGateDecision.Hold(BackfillHoldReason.STALE_APPROVAL)
            }

            else -> {
                BackfillGateDecision.Transmit(reconciliation.toBeSubmitted)
            }
        }
    }
}
