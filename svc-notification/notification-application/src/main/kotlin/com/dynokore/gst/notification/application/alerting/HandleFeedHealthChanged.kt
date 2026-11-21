package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.DefaultAlertKind
import com.dynokore.gst.notification.domain.alerting.FEED_ALERT_CLASS
import com.dynokore.gst.notification.domain.alerting.feedAlertKind
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

@JvmInline
value class AlertId(
    val value: String,
)

/** The state a consumer holds per tenant, feed, entityRef, period and control. */
data class FeedHealthState(
    val tenant: TenantId,
    val feed: String,
    val entityRef: String,
    val period: String,
    val control: String,
    val state: String,
    val stateVersion: Long?,
    val alertId: AlertId?,
    /** The resolved recipient user IDs recorded at the raise; empty for CLEARED. */
    val recipients: List<String>,
)

interface FeedHealthStates {
    fun find(
        tenant: TenantId,
        feed: String,
        entityRef: String,
        period: String,
        control: String,
    ): FeedHealthState?

    fun save(state: FeedHealthState)
}

data class FeedAlertDraft(
    val tenant: TenantId,
    val alertClass: AlertClass,
    val trigger: String,
    val entityRef: String,
    val periodRef: String,
    val raisedAt: Instant,
)

interface FeedAlertStore {
    /** The id of the open alert for the key: the new one, or the existing one. */
    fun raise(draft: FeedAlertDraft): AlertId

    /** Resolves the open alert unless another feed is still FAILED for the same key; true when resolved. */
    fun resolveIfNoFeedFailed(
        tenant: TenantId,
        entityRef: String,
        period: String,
        control: String,
        at: Instant,
    ): Boolean
}

enum class FeedHealthResult { APPLIED_RAISED, APPLIED_CLEARED, IGNORED_STALE }

/** No default recipient exists for the alert; nothing was written, the caller retries or dead-letters. */
class FeedHealthNotDelivered(
    val kind: DefaultAlertKind,
) : RuntimeException("No recipient for feed health alert kind $kind")

/** Handles FeedHealthChanged (INV-REQ-910): one COVERAGE alert per failed control, guarded by stateVersion. */
class HandleFeedHealthChanged(
    private val states: FeedHealthStates,
    private val alerts: FeedAlertStore,
    private val recipients: ResolveDefaultRecipients,
    private val clock: Clock,
) {
    fun handle(
        tenant: TenantId,
        payload: Map<String, Any?>,
    ): FeedHealthResult {
        val event = read(payload)
        val feed = event.feed
        val entityRef = event.entityRef
        val period = event.period
        val control = event.control
        val state = event.state
        val version = event.version
        val kind = feedAlertKind(control)
        val held = states.find(tenant, feed, entityRef, period, control)
        if (!applies(held, version)) return FeedHealthResult.IGNORED_STALE
        return if (state == FAILED) {
            val people =
                when (val result = recipients.resolve(tenant, kind)) {
                    is DefaultRecipientResult.Recipients -> result.people
                    is DefaultRecipientResult.NoRecipient -> throw FeedHealthNotDelivered(result.kind)
                }
            val draft = FeedAlertDraft(tenant, FEED_ALERT_CLASS, control, entityRef, period, clock.instant())
            val id = alerts.raise(draft)
            states.save(FeedHealthState(tenant, feed, entityRef, period, control, state, version, id, people))
            FeedHealthResult.APPLIED_RAISED
        } else {
            states.save(
                FeedHealthState(tenant, feed, entityRef, period, control, state, version, held?.alertId, emptyList()),
            )
            alerts.resolveIfNoFeedFailed(tenant, entityRef, period, control, clock.instant())
            FeedHealthResult.APPLIED_CLEARED
        }
    }

    private data class Event(
        val feed: String,
        val entityRef: String,
        val period: String,
        val control: String,
        val state: String,
        val version: Long?,
    )

    private fun read(payload: Map<String, Any?>): Event {
        val control = text(payload, "control", Int.MAX_VALUE)
        if (control !in CONTROLS) throw ContractViolation("control is not a known feed control")
        val state = text(payload, "state", Int.MAX_VALUE)
        if (state != FAILED && state != CLEARED) throw ContractViolation("state must be FAILED or CLEARED")
        return Event(
            text(payload, "feed", Int.MAX_VALUE),
            text(payload, "entityRef", MAX_ENTITY_REF),
            text(payload, "period", MAX_PERIOD),
            control,
            state,
            version(payload),
        )
    }

    private fun applies(
        held: FeedHealthState?,
        version: Long?,
    ): Boolean =
        when {
            held == null -> true
            version == null -> false
            else -> held.stateVersion == null || version > held.stateVersion
        }

    private fun text(
        payload: Map<String, Any?>,
        key: String,
        max: Int,
    ): String {
        val value = payload[key] as? String
        if (value.isNullOrBlank()) throw ContractViolation("$key is required")
        if (value.length > max) throw ContractViolation("$key is longer than $max")
        return value
    }

    private fun version(payload: Map<String, Any?>): Long? {
        val value = payload["stateVersion"] ?: return null
        val number =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> throw ContractViolation("stateVersion must be an integer")
            }
        if (number <= 0) throw ContractViolation("stateVersion must be positive")
        return number
    }

    private companion object {
        val CONTROLS = setOf("SEQUENCE", "COUNT", "VALUE", "WATERMARK", "MANIFEST")
        const val FAILED = "FAILED"
        const val CLEARED = "CLEARED"
        const val MAX_ENTITY_REF = 64
        const val MAX_PERIOD = 32
    }
}
