package com.dynokore.gst.submission.application.amendment

import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome
import com.dynokore.gst.submission.domain.amendment.AmendmentOutcomeRule
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.isTransmitted
import com.dynokore.gst.submission.domain.routing.ChangeClass

sealed interface AmendmentDecisionResult {
    /** No earlier version and no class: nothing to decide. */
    data object NoAmendment : AmendmentDecisionResult

    data class Decided(
        val outcome: AmendmentOutcome,
    ) : AmendmentDecisionResult

    /** Never guessed (fail closed). */
    data class Undecided(
        val reason: UndecidedReason,
    ) : AmendmentDecisionResult
}

enum class UndecidedReason { CLASS_ABSENT, CLASS_WITHOUT_EARLIER_VERSION }

/** Decides the consequence of the amendment class carried by PayloadReady (INV-REQ-794); reads no store. */
class DecideAmendment {
    /** @throws IllegalArgumentException when [amendmentClass] is not one of the five class names. */
    fun decide(
        earlierStates: List<LifecycleState>,
        amendmentClass: String?,
    ): AmendmentDecisionResult {
        val earlierExists = earlierStates.isNotEmpty()
        return when {
            amendmentClass == null && !earlierExists -> {
                AmendmentDecisionResult.NoAmendment
            }

            amendmentClass == null -> {
                AmendmentDecisionResult.Undecided(UndecidedReason.CLASS_ABSENT)
            }

            else -> {
                val changeClass = classOf(amendmentClass)
                if (earlierExists) {
                    AmendmentDecisionResult.Decided(
                        AmendmentOutcomeRule.decide(changeClass, earlierStates.any { isTransmitted(it) }),
                    )
                } else {
                    AmendmentDecisionResult.Undecided(UndecidedReason.CLASS_WITHOUT_EARLIER_VERSION)
                }
            }
        }
    }

    private fun classOf(name: String): ChangeClass {
        val known = ChangeClass.entries.filter { it != ChangeClass.UNKNOWN }.firstOrNull { it.name == name }
        return requireNotNull(known) { "unknown amendment class: $name" }
    }
}
