package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.DEP_UNACTIVATED_PACK
import com.dynokore.gst.notification.domain.alerting.PackAlertDraft
import com.dynokore.gst.notification.domain.alerting.PackCurrency
import com.dynokore.gst.notification.domain.alerting.PackDecision
import com.dynokore.gst.notification.domain.alerting.PackStaleness
import com.dynokore.gst.notification.domain.alerting.PackStatus
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.platform.tenancykernel.TenantId

/** The stored policy of the class PACK_STALENESS. */
data class PackPolicy(
    val recipients: List<String>,
    val tiers: List<PolicyTier>,
)

/** Reads the policy of the class PACK_STALENESS at the tenant-wide reference; null when no row exists. */
interface PackPolicySource {
    fun policy(tenant: TenantId): PackPolicy?
}

interface PackAlertStore {
    /** True when newly raised; false when an alert of the same tenant, class, trigger and reference is already open. */
    fun raise(
        tenant: TenantId,
        draft: PackAlertDraft,
    ): Boolean

    /** True when one or more open alerts changed state. */
    fun resolveOpen(tenant: TenantId): Boolean
}

/**
 * No alert could be delivered (no policy or no recipient). The adapter retries or dead-letters the event
 * and never drops it (precedent: [BreakGlassNotDelivered]).
 */
class PackAlertNotDelivered(
    val reason: String,
) : RuntimeException("pack staleness alert not delivered: $reason")

sealed class HandlePackCurrencyChangedResult {
    data object Raised : HandlePackCurrencyChangedResult()

    data object AlreadyOpen : HandlePackCurrencyChangedResult()

    data object Resolved : HandlePackCurrencyChangedResult()

    data object NothingOpen : HandlePackCurrencyChangedResult()

    data object Ignored : HandlePackCurrencyChangedResult()
}

/** Handles PackCurrencyChanged (INV-Q-20864 A). Idempotency comes from the platform inbox of the consumer. */
class HandlePackCurrencyChanged(
    private val policy: PackPolicySource,
    private val store: PackAlertStore,
) {
    fun handle(
        tenant: TenantId,
        payload: Map<String, Any?>,
    ): HandlePackCurrencyChangedResult {
        val event = read(payload)
        if (event.depCode == DEP_UNACTIVATED_PACK && event.status == PackStatus.RAISED) {
            val stored = policy.policy(tenant) ?: throw PackAlertNotDelivered("no PACK_STALENESS policy")
            if (stored.recipients.isEmpty()) throw PackAlertNotDelivered("PACK_STALENESS policy has no recipient")
            return when (val decision = PackStaleness.decide(event, stored.tiers)) {
                is PackDecision.Raise -> if (store.raise(tenant, decision.draft)) {
                    HandlePackCurrencyChangedResult.Raised
                } else {
                    HandlePackCurrencyChangedResult.AlreadyOpen
                }
                else -> HandlePackCurrencyChangedResult.Ignored
            }
        }
        return when (PackStaleness.decide(event, emptyList())) {
            PackDecision.Resolve ->
                if (store.resolveOpen(tenant)) {
                    HandlePackCurrencyChangedResult.Resolved
                } else {
                    HandlePackCurrencyChangedResult.NothingOpen
                }
            else -> HandlePackCurrencyChangedResult.Ignored
        }
    }

    private fun read(payload: Map<String, Any?>): PackCurrency {
        val depCode = payload["depCode"] as? String
        if (depCode.isNullOrBlank()) throw ContractViolation("depCode is required")
        val status =
            PackStatus.entries.firstOrNull { it.name == payload["status"] }
                ?: throw ContractViolation("status must be RAISED or CLEARED")
        val days =
            when (val raw = payload["daysToEffectiveDate"]) {
                null -> null
                is Int -> raw
                is Long -> raw.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
                else -> null
            }
        val present = payload["daysToEffectiveDate"] != null
        if (present && (days == null || days < 0)) throw ContractViolation("daysToEffectiveDate must be a non-negative integer")
        return PackCurrency(depCode, status, days)
    }
}
