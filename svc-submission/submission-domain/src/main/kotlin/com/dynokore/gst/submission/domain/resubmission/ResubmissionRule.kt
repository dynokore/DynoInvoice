package com.dynokore.gst.submission.domain.resubmission

/**
 * The recorded-data facts of one version (INV-REQ-546). Only projected payload fields can be passed in, so no
 * return-level fact can trigger a resubmission.
 */
data class ResubmissionFacts(
    val transmitted: Boolean,
    val lastAcknowledged: Map<String, String>,
    val projected: Map<String, String>,
)

/** Why no resubmission is made. */
enum class NoResubmissionReason {
    NO_RECORDED_CHANGE,
    NOT_TRANSMITTED,
}

/** Why the rule cannot decide. */
enum class ResubmissionUndecidedReason {
    FACTS_MISSING,
    BLANK_FIELD_NAME,
    NO_ACKNOWLEDGED_BASELINE,
}

/** The result carries no Doc UUID: the caller resubmits under the Doc UUID it already holds. */
sealed interface ResubmissionDecision

/** The recorded fields in [changedFields] changed; sorted ascending, no duplicates. */
data class Resubmit(
    val changedFields: List<String>,
) : ResubmissionDecision

data class NoResubmission(
    val reason: NoResubmissionReason,
) : ResubmissionDecision

data class Undecided(
    val reason: ResubmissionUndecidedReason,
) : ResubmissionDecision

/** Decides whether a change to the recorded data requires resubmission under the same Doc UUID (INV-REQ-546). */
object ResubmissionRule {
    fun decide(facts: ResubmissionFacts?): ResubmissionDecision =
        when {
            facts == null -> {
                Undecided(ResubmissionUndecidedReason.FACTS_MISSING)
            }

            (facts.lastAcknowledged.keys + facts.projected.keys).any { it.isBlank() } -> {
                Undecided(ResubmissionUndecidedReason.BLANK_FIELD_NAME)
            }

            !facts.transmitted -> {
                NoResubmission(NoResubmissionReason.NOT_TRANSMITTED)
            }

            facts.lastAcknowledged.isEmpty() -> {
                Undecided(ResubmissionUndecidedReason.NO_ACKNOWLEDGED_BASELINE)
            }

            else -> {
                compare(facts.lastAcknowledged, facts.projected)
            }
        }

    private fun compare(
        last: Map<String, String>,
        now: Map<String, String>,
    ): ResubmissionDecision {
        val changed = (last.keys + now.keys).filter { last[it] != now[it] }.sorted()
        return if (changed.isEmpty()) NoResubmission(NoResubmissionReason.NO_RECORDED_CHANGE) else Resubmit(changed)
    }
}
