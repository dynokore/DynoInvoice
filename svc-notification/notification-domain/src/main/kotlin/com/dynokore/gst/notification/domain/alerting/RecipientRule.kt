package com.dynokore.gst.notification.domain.alerting

import java.time.LocalDate

/** A sole-operator acknowledgement as claimed on a policy save. */
data class AcknowledgementClaim(
    val accountablePerson: String?,
    val effectiveFrom: LocalDate?,
)

data class RecordedAcknowledgement(
    val accountablePerson: String,
    val effectiveFrom: LocalDate,
)

enum class RecipientRefusal {
    BLANK_RECIPIENT,
    TOO_FEW_RECIPIENTS,
    ACCOUNTABLE_PERSON_MISSING,
    BACK_DATED,
}

sealed interface RecipientDecision {
    /** [acknowledgement] is null when two distinct recipients made it unnecessary. */
    data class Saved(
        val acknowledgement: RecordedAcknowledgement?,
    ) : RecipientDecision

    data class Refused(
        val code: String,
        val reason: RecipientRefusal,
    ) : RecipientDecision
}

/** INV-REQ-1115: two distinct recipients, or a named, effective-dated, not back-dated sole-operator acknowledgement. */
object RecipientRule {
    fun decide(
        recipients: List<String>,
        acknowledgement: AcknowledgementClaim?,
        businessDate: LocalDate,
    ): RecipientDecision {
        val person = acknowledgement?.accountablePerson
        val from = acknowledgement?.effectiveFrom
        return when {
            recipients.any { it.isBlank() } -> {
                RecipientDecision.Refused(POL_OUT_OF_BOUNDS, RecipientRefusal.BLANK_RECIPIENT)
            }

            recipients.distinct().size >= 2 -> {
                RecipientDecision.Saved(null)
            }

            acknowledgement == null -> {
                RecipientDecision.Refused(POL_CONSTRAINT, RecipientRefusal.TOO_FEW_RECIPIENTS)
            }

            person == null || person.isBlank() -> {
                RecipientDecision.Refused(POL_OUT_OF_BOUNDS, RecipientRefusal.ACCOUNTABLE_PERSON_MISSING)
            }

            from != null && from.isBefore(businessDate) -> {
                RecipientDecision.Refused(POL_BACK_DATED, RecipientRefusal.BACK_DATED)
            }

            else -> {
                RecipientDecision.Saved(RecordedAcknowledgement(person, from ?: businessDate))
            }
        }
    }
}
