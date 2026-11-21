package com.dynokore.gst.submission.domain.amendment

import com.dynokore.gst.submission.domain.routing.ChangeClass

/** The consequence of a recorded amendment (INV-REQ-578 behavior 3, INV-REQ-579 behavior 4). */
sealed interface AmendmentOutcome {
    /** Resubmit under the same Doc UUID, raising [raised] codes. */
    data class Resubmit(
        val raised: List<String>,
    ) : AmendmentOutcome

    /** Hold the version with [code]. */
    data class Hold(
        val code: String,
    ) : AmendmentOutcome

    /** The earlier version is superseded. */
    data object Supersede : AmendmentOutcome

    /** Reject with [code]. */
    data class Reject(
        val code: String,
    ) : AmendmentOutcome
}

/** The outcome is a function of the carried class and the transmitted flag only; never of field values. */
object AmendmentOutcomeRule {
    private const val REJECT_STRUCTURAL = "ING-012"

    /** @throws IllegalArgumentException for [ChangeClass.UNKNOWN] (the caller maps an absent class to undecided). */
    fun decide(
        changeClass: ChangeClass,
        transmitted: Boolean,
    ): AmendmentOutcome =
        when (changeClass) {
            ChangeClass.UNKNOWN -> {
                throw IllegalArgumentException("an unknown change class has no outcome")
            }

            ChangeClass.STRUCTURAL -> {
                AmendmentOutcome.Reject(REJECT_STRUCTURAL)
            }

            ChangeClass.NON_MONETARY -> {
                if (transmitted) AmendmentOutcome.Resubmit(emptyList()) else AmendmentOutcome.Supersede
            }

            ChangeClass.IDENTITY -> {
                if (transmitted) AmendmentOutcome.Hold("SUB-016") else AmendmentOutcome.Supersede
            }

            ChangeClass.PERIOD_SHIFTING -> {
                if (transmitted) AmendmentOutcome.Resubmit(listOf("PER-006")) else AmendmentOutcome.Supersede
            }

            ChangeClass.MONETARY -> {
                if (transmitted) AmendmentOutcome.Hold("SUB-015") else AmendmentOutcome.Supersede
            }
        }
}
