package com.dynokore.gst.submission.domain.correction

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/** Reasons a credit note is not submittable on the correction path. */
enum class CorrectionReason(
    val code: String,
) {
    NETTED_INTO_REVISION("NETTED_INTO_REVISION"),
}

/** The correction path of a received credit note (INV-REQ-559 behavior 2, INV-Q-6623 option A). */
sealed interface CorrectionDecision {
    data class SubmitSeparately(
        val declarationIgnored: Boolean,
    ) : CorrectionDecision

    data object Wait : CorrectionDecision

    data class NotSubmittable(
        val reason: CorrectionReason,
    ) : CorrectionDecision

    data object SubmitAsPosted : CorrectionDecision
}

/** Pure: no state, no clock; equal input gives equal output. */
object CreditNoteCorrection {
    fun decide(
        originalVersions: List<LifecycleState>,
        nettingDeclared: Boolean,
    ): CorrectionDecision {
        val classes = originalVersions.map(OriginalStanding::classOf)
        return when {
            classes.any { it == StateClass.AT_IRAS } -> {
                CorrectionDecision.SubmitSeparately(
                    declarationIgnored = nettingDeclared,
                )
            }

            classes.any { it == StateClass.SENT_OUT } -> {
                CorrectionDecision.Wait
            }

            nettingDeclared -> {
                CorrectionDecision.NotSubmittable(CorrectionReason.NETTED_INTO_REVISION)
            }

            classes.any { it == StateClass.QUEUED_CLASS } -> {
                CorrectionDecision.Wait
            }

            else -> {
                CorrectionDecision.SubmitAsPosted
            }
        }
    }
}
