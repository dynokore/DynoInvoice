package com.dynokore.gst.controlPlane.adapters.events

import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlert
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper

/**
 * The AccreditationAlertRaised platform event (INV-REQ-1365, INV-Q-7336 A), written to the outbox in the open
 * transaction of the task step. The platform is not a tenant, so it is written under `TenantId.PLATFORM` and
 * `EntityRef.PLATFORM` (INV-REQ-1226 b7). Delivery to operators is the notification-svc slice of INV-Q-7316 A.
 */
class AccreditationAlertsOutbox(
    private val outbox: OutboxWriter,
) : AccreditationAlerts {
    private val json = JsonMapper.builder().build()

    override fun raise(
        alert: AccreditationAlert,
        request: RequestContext,
    ) {
        val data =
            linkedMapOf<String, Any>(
                "alertRef" to alert.alertRef,
                "taskRef" to alert.taskRef,
                "reason" to alert.reason.name,
                "raisedAt" to alert.raisedAt.toString(),
            )
        alert.releaseRef?.let { data["releaseRef"] = it }
        alert.currentUntil?.let { data["currentUntil"] = it.toString() }
        outbox.append(
            listOf(OutboundEvent(TYPE, json.writeValueAsString(data))),
            EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = alert.taskRef,
                aggregateVersion = null,
                correlation = CorrelationId(request.correlationId),
                causation = CausationId(request.causationId),
            ),
        )
    }

    companion object {
        /** The platform event type of service-runtime's contract (INV-Q-7336 A), not a control-plane type. */
        const val TYPE = "com.dynokore.gst.platform.AccreditationAlertRaised.v1"
    }
}
