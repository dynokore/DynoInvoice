package com.dynokore.gst.submission.domain.relay

import com.dynokore.gst.submission.domain.scope.NotSubmittableReason
import com.dynokore.gst.submission.domain.typing.NetworkPath
import com.dynokore.gst.submission.domain.typing.SubmissionType

/** The outcome of the checks of payload-svc: `PayloadReady` with its submission type, or `PayloadRejected`. */
sealed interface PayloadOutcome {
    data class Ready(
        val submissionType: SubmissionType,
    ) : PayloadOutcome

    data object Rejected : PayloadOutcome
}

/** The only facts the decision reads; no entity-mode, tenant or override input exists. */
data class RelayOutcomeFacts(
    val networkPath: NetworkPath?,
    val outcome: PayloadOutcome,
)

/** Why the decision is held. */
enum class HoldReason { FACTS_MISSING, }

/** The decision of [RelayOutcomeRouting.decide]. */
sealed interface RelayOutcomeDecision {
    data object SendNetworkDocument : RelayOutcomeDecision

    data class MarkNotSubmittable(
        val reason: NotSubmittableReason,
    ) : RelayOutcomeDecision

    data object OrdinaryPath : RelayOutcomeDecision

    data class Hold(
        val reason: HoldReason,
    ) : RelayOutcomeDecision
}

/** What a payload outcome does to a Relay-issued document (INV-REQ-324 behavior 8); pure, fail closed. */
object RelayOutcomeRouting {
    fun decide(facts: RelayOutcomeFacts?): RelayOutcomeDecision =
        when (facts?.networkPath) {
            null -> RelayOutcomeDecision.Hold(HoldReason.FACTS_MISSING)
            NetworkPath.RELAY_ISSUANCE -> decideRelay(facts.outcome)
            else -> RelayOutcomeDecision.OrdinaryPath
        }

    private fun decideRelay(outcome: PayloadOutcome): RelayOutcomeDecision =
        when (outcome) {
            PayloadOutcome.Rejected -> {
                RelayOutcomeDecision.MarkNotSubmittable(NotSubmittableReason.ISSUANCE_REFUSED)
            }

            is PayloadOutcome.Ready -> {
                if (outcome.submissionType == SubmissionType.TYPE_1A) {
                    RelayOutcomeDecision.SendNetworkDocument
                } else {
                    RelayOutcomeDecision.OrdinaryPath
                }
            }
        }
}
