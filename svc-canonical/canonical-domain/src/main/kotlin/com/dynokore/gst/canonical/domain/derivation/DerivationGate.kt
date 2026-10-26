package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Complete
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.HeldBy
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Unavailable

/** The hold of a document whose derivation waits for rules-svc or masterdata-svc (catalogue: HOLD, AUTO_RELEASE). */
const val UNAVAILABLE_CODE = "ING-016"

private val HOLD_CODE = Regex("^[A-Z]{2,10}-[0-9]{3}$")

private val RULES_STEPS = setOf(DerivationStep.PACK_VERSIONS, DerivationStep.LINE_SCOPE_AND_CATEGORY)
private val MASTERDATA_STEPS = setOf(DerivationStep.PARTY_IDENTIFIERS)

/**
 * The only code that answers "may this document leave INGESTED" (INV-REQ-479 behavior 2; INV-Q-7123 A). Pure: no state,
 * no clock; a second call with all eight steps complete releases a held document (INV-REQ-1208 behavior 2).
 */
object DerivationGate {
    /** @throws IllegalArgumentException for a missing step, a dependency not allowed at its step, a bad code. */
    fun decide(outcomes: Map<DerivationStep, StepOutcome>): GateDecision {
        for (step in DerivationStep.entries) {
            val outcome = requireNotNull(outcomes[step]) { "no outcome for step $step" }
            if (outcome is Unavailable) {
                val allowed = if (outcome.dependency == Dependency.RULES_SVC) RULES_STEPS else MASTERDATA_STEPS
                require(step in allowed) { "${outcome.dependency} is not a dependency of step $step" }
            } else if (outcome is HeldBy) {
                require(HOLD_CODE.matches(outcome.code)) { "malformed hold code for step $step" }
            }
        }
        val reasons =
            DerivationStep.entries
                .mapNotNull { step ->
                    when (val outcome = outcomes.getValue(step)) {
                        Complete -> null
                        is Unavailable -> UNAVAILABLE_CODE
                        is HeldBy -> outcome.code
                    }
                }.distinct()
        return if (reasons.isEmpty()) GateDecision.Proceed else GateDecision.Held(reasons)
    }
}
