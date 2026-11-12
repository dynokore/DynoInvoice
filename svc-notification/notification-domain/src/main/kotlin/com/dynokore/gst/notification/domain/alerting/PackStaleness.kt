package com.dynokore.gst.notification.domain.alerting

/** Trigger of the pack staleness alert; the same literal as the contract value (INV-Q-20864 A). */
const val PACK_ALERT_TRIGGER = "PACK_NOT_ACTIVATED"

/** Deployment alert code raised while a newer pack is published but not activated (INV-REQ-1080). */
const val DEP_UNACTIVATED_PACK = "DEP-001"

/** Status of a pack currency event. */
enum class PackStatus { RAISED, CLEARED }

/** The pack currency event as the decision needs it; [daysToEffectiveDate] is null when the event carries none. */
data class PackCurrency(
    val depCode: String,
    val status: PackStatus,
    val daysToEffectiveDate: Int?,
) {
    init {
        require(depCode.isNotBlank()) { "depCode must not be blank" }
        require(daysToEffectiveDate == null || daysToEffectiveDate >= 0) { "daysToEffectiveDate must not be negative" }
    }
}

data class PackAlertDraft(
    val alertClass: AlertClass,
    val trigger: String,
    val tier: Int,
    val entityRef: String,
)

sealed interface PackDecision {
    data class Raise(
        val draft: PackAlertDraft,
    ) : PackDecision

    data object Resolve : PackDecision

    data object Ignore : PackDecision
}

/**
 * Decides the tenant-wide pack staleness alert from the pack currency event (INV-Q-20864 A, INV-REQ-1080).
 * Reads only its arguments and keeps no state. Every threshold is the [PolicyTier.daysToDue] of the stored
 * policy (INV-REQ-1113), never a constant; the tier rule is the one of [ComplianceAlert].
 */
object PackStaleness {
    private const val FIRST_TIER = 1

    fun decide(
        event: PackCurrency,
        tiers: List<PolicyTier>,
    ): PackDecision =
        when {
            event.depCode != DEP_UNACTIVATED_PACK -> PackDecision.Ignore
            event.status == PackStatus.CLEARED -> PackDecision.Resolve
            else -> PackDecision.Raise(draft(event.daysToEffectiveDate, tiers))
        }

    private fun draft(
        days: Int?,
        tiers: List<PolicyTier>,
    ): PackAlertDraft {
        val tier =
            if (days == null) {
                FIRST_TIER
            } else {
                tiers
                    .sortedByDescending { it.tier }
                    .firstOrNull { it.daysToDue != null && it.daysToDue >= days }
                    ?.tier ?: FIRST_TIER
            }
        return PackAlertDraft(AlertClass.PACK_STALENESS, PACK_ALERT_TRIGGER, tier, TENANT_WIDE_ENTITY_REF)
    }
}
