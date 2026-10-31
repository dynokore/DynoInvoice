package com.dynokore.gst.submission.domain.schedule

import com.dynokore.gst.submission.domain.typing.SubmissionType
import com.dynokore.gst.submission.domain.typing.TypeDecision

/** Why a document is kept out of the scheduled path. */
enum class ScheduleExclusion { NETWORK_REAL_TIME, TYPE_UNDECIDED }

/** The decision of [ScheduledPathEligibility.decide]. */
sealed interface ScheduleEligibility {
    data class Eligible(
        val type: SubmissionType,
    ) : ScheduleEligibility

    data class NotEligible(
        val exclusion: ScheduleExclusion,
    ) : ScheduleEligibility
}

/** Only Types 1B, 2 and 3 take the scheduled path; Type 1A is submitted by the network in real time (INV-REQ-74). */
object ScheduledPathEligibility {
    fun decide(decision: TypeDecision): ScheduleEligibility =
        when (decision) {
            is TypeDecision.Decided -> {
                when (decision.type) {
                    SubmissionType.TYPE_1A -> ScheduleEligibility.NotEligible(ScheduleExclusion.NETWORK_REAL_TIME)

                    SubmissionType.TYPE_1B,
                    SubmissionType.TYPE_2,
                    SubmissionType.TYPE_3,
                    -> ScheduleEligibility.Eligible(decision.type)
                }
            }

            is TypeDecision.Undecided -> {
                ScheduleEligibility.NotEligible(ScheduleExclusion.TYPE_UNDECIDED)
            }
        }
}
