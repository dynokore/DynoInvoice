package com.dynokore.gst.ingestion.domain.issuance

import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import java.util.UUID

/** What payload-svc answered to the validation of a Relay issuance, reduced to the facts the decision reads. */
sealed interface ValidationAnswer {
    data object Pass : ValidationAnswer

    /** Verdict WARN; [flags] keep payload-svc's order (for example REGISTER_UNAVAILABLE). */
    data class Warn(
        val flags: List<String>,
    ) : ValidationAnswer

    /** Verdict BLOCK; [reasons] keep payload-svc's order. */
    data class Blocked(
        val reasons: List<String>,
    ) : ValidationAnswer

    data object Unavailable : ValidationAnswer
}

/** The only inputs of the decision; [validation] is null when no validation ran. */
data class IssuanceFacts(
    val recording: RecordingOutcome,
    val validation: ValidationAnswer?,
)

enum class UnavailableCause {
    FACTS_MISSING,
    DEPENDENCY_HOLD,
    VALIDATION_UNAVAILABLE,
}

enum class RefusalSource {
    VALIDATION,
    DERIVATION_HOLD,
}

sealed interface IssuanceDecision {
    /** Hand the Peppol document to the AP; [flags] are the warn flags, empty on a pass. */
    data class HandOff(
        val docUuid: UUID,
        val flags: List<String>,
    ) : IssuanceDecision

    /** Answer 422 with [reasons] and hand nothing over. */
    data class Refused(
        val docUuid: UUID,
        val reasons: List<String>,
        val source: RefusalSource,
    ) : IssuanceDecision {
        companion object {
            const val NOT_SUBMITTABLE_REASON = "ISSUANCE_REFUSED"
        }
    }

    /** The API is unavailable (INV-REQ-340) and nothing is handed over. */
    data class ApiUnavailable(
        val cause: UnavailableCause,
    ) : IssuanceDecision

    /** The document was not recorded; nothing is handed over. */
    data class NotRecorded(
        val outcome: RecordingOutcome,
    ) : IssuanceDecision
}

/**
 * Decides the outcome of a Relay issuance (INV-REQ-324, INV-AC-13326): a pure function of the recording answer and the
 * validation answer. The `when` over [RecordingOutcome] has no `else`, so a new member fails to compile (fail closed).
 */
object RelayIssuanceDecision {
    private const val DEPENDENCY_CODE = "ING-016"

    fun decide(facts: IssuanceFacts?): IssuanceDecision {
        if (facts == null) {
            return IssuanceDecision.ApiUnavailable(UnavailableCause.FACTS_MISSING)
        }
        return when (val recording = facts.recording) {
            is RecordingOutcome.Relayed -> {
                if (recording.errorCode == DEPENDENCY_CODE) {
                    IssuanceDecision.ApiUnavailable(UnavailableCause.DEPENDENCY_HOLD)
                } else {
                    IssuanceDecision.NotRecorded(recording)
                }
            }

            is RecordingOutcome.Stale -> {
                IssuanceDecision.NotRecorded(recording)
            }

            is RecordingOutcome.Failed -> {
                IssuanceDecision.NotRecorded(recording)
            }

            is RecordingOutcome.Held -> {
                if (DEPENDENCY_CODE in recording.reasons) {
                    IssuanceDecision.ApiUnavailable(UnavailableCause.DEPENDENCY_HOLD)
                } else {
                    IssuanceDecision.Refused(recording.docUuid, recording.reasons, RefusalSource.DERIVATION_HOLD)
                }
            }

            is RecordingOutcome.New -> {
                validated(recording.docUuid, facts.validation)
            }

            is RecordingOutcome.Amend -> {
                validated(recording.docUuid, facts.validation)
            }

            is RecordingOutcome.Replay -> {
                validated(recording.docUuid, facts.validation)
            }
        }
    }

    private fun validated(
        docUuid: UUID,
        validation: ValidationAnswer?,
    ): IssuanceDecision =
        when (validation) {
            null, ValidationAnswer.Unavailable -> {
                IssuanceDecision.ApiUnavailable(UnavailableCause.VALIDATION_UNAVAILABLE)
            }

            is ValidationAnswer.Blocked -> {
                IssuanceDecision.Refused(docUuid, validation.reasons, RefusalSource.VALIDATION)
            }

            ValidationAnswer.Pass -> {
                IssuanceDecision.HandOff(docUuid, emptyList())
            }

            is ValidationAnswer.Warn -> {
                IssuanceDecision.HandOff(docUuid, validation.flags)
            }
        }
}
