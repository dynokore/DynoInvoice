package com.dynokore.gst.notification.domain.alerting

import java.time.LocalDate

/** Trigger names of the submission contract (INV-T-12841). */
const val TRIGGER_COVERAGE_GAP = "COVERAGE_GAP"
const val TRIGGER_DUE_DATE_THRESHOLD = "DUE_DATE_THRESHOLD"
const val TRIGGER_PAST_DUE = "PAST_DUE"

/** The measured values that drive the coverage and deadline alerts, per entity and prescribed period (INV-REQ-1069). */
data class ComplianceValues(
    val entityRef: String,
    val periodRef: String,
    val measuredOn: LocalDate,
    val documentsWithoutSubmission: Int,
    val documentsPastDue: Int,
    val daysToNearestDeadline: Int?,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(documentsWithoutSubmission >= 0) { "documentsWithoutSubmission must be at least 0" }
        require(documentsPastDue >= 0) { "documentsPastDue must be at least 0" }
    }
}

/** One escalation tier of the entity's alert policy (INV-REQ-1113); a negative [daysToDue] is days after the due date. */
data class PolicyTier(
    val tier: Int,
    val daysToDue: Int?,
)

/** Why no alert is raised. */
enum class WithholdReason {
    NOTHING_TO_ALERT,
}

data class ComplianceAlertDraft(
    val alertClass: AlertClass,
    val trigger: String,
    val tier: Int?,
    val entityRef: String,
    val periodRef: String,
)

sealed interface ComplianceAlertDecision {
    data class Raise(
        val drafts: List<ComplianceAlertDraft>,
    ) : ComplianceAlertDecision

    data class Withhold(
        val reason: WithholdReason,
    ) : ComplianceAlertDecision
}

/**
 * Decides the coverage and deadline alerts from the measured values (INV-Q-12767 A, INV-REQ-1069, INV-REQ-1113).
 * Reads only its arguments and keeps no state. The escalation thresholds are the entity's policy [PolicyTier]s, never constants.
 */
object ComplianceAlert {
    private const val FIRST_TIER = 1

    fun decide(
        values: ComplianceValues,
        tiers: List<PolicyTier>,
    ): ComplianceAlertDecision {
        if (values.documentsWithoutSubmission == 0) {
            return ComplianceAlertDecision.Withhold(WithholdReason.NOTHING_TO_ALERT)
        }
        val coverage =
            ComplianceAlertDraft(
                alertClass = AlertClass.COVERAGE,
                trigger = TRIGGER_COVERAGE_GAP,
                tier = FIRST_TIER,
                entityRef = values.entityRef,
                periodRef = values.periodRef,
            )
        val days = values.daysToNearestDeadline ?: return ComplianceAlertDecision.Raise(listOf(coverage))
        val tier =
            tiers
                .sortedByDescending { it.tier }
                .firstOrNull { it.daysToDue != null && it.daysToDue >= days }
                ?.tier ?: FIRST_TIER
        val deadline =
            ComplianceAlertDraft(
                alertClass = AlertClass.DEADLINE,
                trigger = if (days < 0) TRIGGER_PAST_DUE else TRIGGER_DUE_DATE_THRESHOLD,
                tier = tier,
                entityRef = values.entityRef,
                periodRef = values.periodRef,
            )
        return ComplianceAlertDecision.Raise(listOf(coverage, deadline))
    }
}
