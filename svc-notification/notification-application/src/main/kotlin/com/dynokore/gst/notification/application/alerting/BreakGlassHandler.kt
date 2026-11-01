package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.BreakGlassAlert
import com.dynokore.gst.notification.domain.alerting.BreakGlassAlertDraft
import com.dynokore.gst.notification.domain.alerting.BreakGlassAlertRaised
import com.dynokore.gst.notification.domain.alerting.BreakGlassDecision
import com.dynokore.gst.notification.domain.alerting.BreakGlassMessage
import com.dynokore.gst.notification.domain.alerting.BreakGlassMessageParameters
import com.dynokore.gst.notification.domain.alerting.BreakGlassScope
import com.dynokore.gst.notification.domain.alerting.BreakGlassWithheld
import com.dynokore.platform.tenancykernel.TenantId

/** The role whose holders are notified of every break-glass use (INV-REQ-897, INV-REQ-910). */
const val SECURITY_OFFICER_ROLE = "security.officer"

/** The holders of [SECURITY_OFFICER_ROLE] at the scope of the event (INV-Q-8972 A); the adapter calls holdersAt. */
interface SecurityOfficerHolders {
    fun holders(
        tenant: TenantId,
        scope: BreakGlassScope,
    ): Set<String>
}

/** Stores the one alert and sends the message (INV-Q-14877 A: not named AlertSink, taken by the compliance handler). */
interface BreakGlassAlertSink {
    fun raise(
        draft: BreakGlassAlertDraft,
        message: BreakGlassMessageParameters,
    )
}

/**
 * No alert was raised. The adapter must retry or dead-letter the event and never drop it
 * (INV-REQ-910 must_not_happen: an alert class with no recipient); the event is not marked processed.
 */
class BreakGlassNotDelivered(
    val reason: BreakGlassWithheld,
) : RuntimeException("break-glass alert not delivered: $reason")

/** Handles BreakGlassAlertRaised (INV-Q-8972 A, INV-REQ-897) with [BreakGlassAlert]. */
class BreakGlassHandler(
    private val processed: ProcessedEvents,
    private val holders: SecurityOfficerHolders,
    private val sink: BreakGlassAlertSink,
) {
    fun handle(
        eventId: String,
        tenant: TenantId,
        event: BreakGlassAlertRaised,
    ) {
        if (!processed.firstTime(eventId)) return
        val recipients = holders.holders(tenant, event.scope).sorted()
        when (val decision = BreakGlassAlert.decide(event, recipients)) {
            is BreakGlassDecision.Raise -> sink.raise(decision.draft, BreakGlassMessage.of(event))
            is BreakGlassDecision.Withhold -> throw BreakGlassNotDelivered(decision.reason)
        }
    }
}
