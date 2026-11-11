package com.dynokore.gst.submission.domain.withdrawal

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.isTransmitted

/** The catalogue key of the withdrawal (INV-T-10134); its only holder is tax.approver (INV-Q-10127 A). */
const val WITHDRAW_PERMISSION = "submission.withdraw"

/** The longest reason, in code points (WithdrawRequest.reason maxLength, JSON Schema counts code points). */
private const val MAX_REASON_CODE_POINTS = 500

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/** The refusals of `withdrawSubmission`: catalogue string and HTTP status (INV-Q-6942 A). */
enum class RefusalCode(
    val code: String,
    val httpStatus: Int,
) {
    SRT_001("SRT-001", HTTP_BAD_REQUEST),
    SRT_004("SRT-004", HTTP_FORBIDDEN),
    SRT_005("SRT-005", HTTP_NOT_FOUND),
    SUB_021("SUB-021", HTTP_CONFLICT),
}

/**
 * [callerHoldsPermission] is true only when the authority decision for [WITHDRAW_PERMISSION] allowed the caller.
 * [versions] is oldest first; a document with no payload version yet is its one document-level state; empty means no
 * such document.
 */
data class WithdrawalInput(
    val callerHoldsPermission: Boolean,
    val reason: String?,
    val versions: List<LifecycleState>,
)

sealed interface WithdrawalDecision {
    /** The current, last, version becomes WITHDRAWN. */
    data object Withdraw : WithdrawalDecision

    /** A no-op repeat. */
    data object AlreadyWithdrawn : WithdrawalDecision

    data class Refused(
        val code: RefusalCode,
    ) : WithdrawalDecision

    companion object {
        /**
         * Pure: no state, no clock; equal input gives equal output. The first rule that applies wins (INV-Q-6942 A):
         * no document, no permission, bad reason, already withdrawn, sent to the Access Point, last version not open.
         */
        fun decide(input: WithdrawalInput): WithdrawalDecision {
            val versions = input.versions
            return when {
                versions.isEmpty() -> Refused(RefusalCode.SRT_005)
                !input.callerHoldsPermission -> Refused(RefusalCode.SRT_004)
                !validReason(input.reason) -> Refused(RefusalCode.SRT_001)
                versions.last() == LifecycleState.WITHDRAWN -> AlreadyWithdrawn
                versions.any(::wasSentToAccessPoint) -> Refused(RefusalCode.SUB_021)
                withdrawable(versions.last()) -> Withdraw
                else -> Refused(RefusalCode.SUB_021)
            }
        }

        /** A REJECTED version reached the Access Point; [isTransmitted] itself keeps its eight states. */
        fun wasSentToAccessPoint(state: LifecycleState): Boolean =
            isTransmitted(state) || state == LifecycleState.REJECTED

        private fun validReason(reason: String?): Boolean =
            reason != null && reason.isNotBlank() && reason.codePointCount(0, reason.length) <= MAX_REASON_CODE_POINTS

        /** Exhaustive over [LifecycleState]: a new member fails to compile here. */
        private fun withdrawable(state: LifecycleState): Boolean =
            when (state) {
                LifecycleState.INGESTED,
                LifecycleState.HELD,
                LifecycleState.BLOCKED,
                LifecycleState.READY,
                LifecycleState.AWAITING_PRECEDING,
                LifecycleState.QUEUED,
                -> true

                LifecycleState.CANCELLED,
                LifecycleState.NOT_SUBMITTABLE,
                LifecycleState.WITHDRAWN,
                LifecycleState.REJECTED,
                LifecycleState.SENT,
                LifecycleState.SENT_UNCONFIRMED,
                LifecycleState.RETRY,
                LifecycleState.DEAD_LETTERED,
                LifecycleState.ACKNOWLEDGED,
                LifecycleState.SUPERSEDED,
                LifecycleState.NETWORK_ISSUED,
                LifecycleState.EXTERNALLY_SUBMITTED,
                -> false
            }
    }
}
