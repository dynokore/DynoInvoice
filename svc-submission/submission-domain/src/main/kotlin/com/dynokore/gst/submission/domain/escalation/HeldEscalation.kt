package com.dynokore.gst.submission.domain.escalation

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.Duration
import java.time.Instant

/** The catalogue code of a document held for an unavailable dependency (error-codes.yaml, severity HOLD). */
const val HOLD_CODE = "ING-016"

/**
 * @property holdReasons the codes of the DocumentHeld event.
 * @property dueAt the instant of the due date of the entry's period, or null when unknown.
 * @property margin the effective safety margin, computed by the caller; never negative.
 * @property asOf the instant of the evaluation.
 */
data class HeldEscalationInput(
    val state: LifecycleState,
    val holdReasons: List<String>,
    val dueAt: Instant?,
    val margin: Duration,
    val asOf: Instant,
)

enum class EscalationReason { MARGIN_REACHED, NO_DUE_DATE }

/** The decision of [HeldEscalation.decide]; no member is a submit action (INV-BR-217). */
sealed interface EscalationDecision {
    data object Wait : EscalationDecision

    data class Escalate(
        val reason: EscalationReason,
    ) : EscalationDecision
}

/** INV-REQ-1208 item 3: a document held with ING-016 is escalated from its due date minus the safety margin. */
object HeldEscalation {
    private val COVERED_STATES = setOf(LifecycleState.INGESTED, LifecycleState.HELD)

    fun decide(input: HeldEscalationInput): EscalationDecision {
        require(!input.margin.isNegative) { "margin must not be negative: ${input.margin}" }
        val covered = input.state in COVERED_STATES && HOLD_CODE in input.holdReasons
        return if (covered) escalation(input) else EscalationDecision.Wait
    }

    // asOf + margin >= dueAt, written as a comparison of durations so that no Instant is built past its range.
    private fun escalation(input: HeldEscalationInput): EscalationDecision {
        val dueAt = input.dueAt ?: return EscalationDecision.Escalate(EscalationReason.NO_DUE_DATE)
        val reached = Duration.between(input.asOf, dueAt) <= input.margin
        return if (reached) EscalationDecision.Escalate(EscalationReason.MARGIN_REACHED) else EscalationDecision.Wait
    }
}
