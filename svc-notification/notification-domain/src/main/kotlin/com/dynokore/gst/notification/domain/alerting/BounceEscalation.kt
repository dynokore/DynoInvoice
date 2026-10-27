package com.dynokore.gst.notification.domain.alerting

enum class NoEscalation {
    NOT_DEADLINE,
    ACKNOWLEDGED,
    NOT_ONE_RECIPIENT,
    NOT_BOUNCED,
    NO_NEXT_TIER,
}

sealed interface EscalationDecision {
    data class Escalate(
        val toTier: Int,
    ) : EscalationDecision

    data class Stay(
        val reason: NoEscalation,
    ) : EscalationDecision
}

/** INV-REQ-819: a bounced deadline alert with one distinct, unacknowledged recipient moves to the next tier. */
object BounceEscalation {
    // The six-argument signature is fixed by INV-T-6759 (INV-Q-6749 A).
    @Suppress("LongParameterList")
    fun decide(
        alertClass: AlertClass,
        acknowledged: Boolean,
        recipients: List<String>,
        bouncedRecipients: List<String>,
        currentTier: Int,
        highestTier: Int,
    ): EscalationDecision {
        val distinct = recipients.distinct()
        return when {
            alertClass != AlertClass.DEADLINE -> EscalationDecision.Stay(NoEscalation.NOT_DEADLINE)
            acknowledged -> EscalationDecision.Stay(NoEscalation.ACKNOWLEDGED)
            distinct.size != 1 -> EscalationDecision.Stay(NoEscalation.NOT_ONE_RECIPIENT)
            distinct.single() !in bouncedRecipients -> EscalationDecision.Stay(NoEscalation.NOT_BOUNCED)
            currentTier >= highestTier -> EscalationDecision.Stay(NoEscalation.NO_NEXT_TIER)
            else -> EscalationDecision.Escalate(currentTier + 1)
        }
    }
}
