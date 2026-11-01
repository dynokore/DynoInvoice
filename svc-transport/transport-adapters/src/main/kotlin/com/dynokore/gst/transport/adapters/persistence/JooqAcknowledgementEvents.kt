package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.application.AcknowledgementEvents
import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.domain.AcknowledgementReceived
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Writes the `AcknowledgementReceived` outbox row (INV-REQ-585 behaviour 4) in the open transaction and tenant binding
 * of the unit of work, so it shares the transaction of the ledger row.
 */
class JooqAcknowledgementEvents(
    private val outbox: OutboxWriter,
) : AcknowledgementEvents {
    private val mapper = JsonMapper.builder().build()

    override fun publish(
        owner: AttemptOwnerRef,
        event: AcknowledgementReceived,
    ) {
        val payload =
            linkedMapOf<String, Any>(
                "adapter" to event.adapter.value,
                "apNotificationId" to event.apNotificationId,
                "docUuid" to event.docUuid.toString(),
                "version" to event.version,
                "acknowledgementId" to event.acknowledgementId,
                "outcome" to event.outcome.name,
            )
        if (event.reasons.isNotEmpty()) {
            payload["reasons"] = event.reasons.map { mapOf("code" to it.code, "message" to it.message) }
        }
        val correlation = "ap:${event.adapter.value}:${event.apNotificationId}"
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, mapper.writeValueAsString(payload))),
            EventContext(
                TenantId(UUID.fromString(owner.tenant.value)),
                EntityRef(owner.entity.value),
                DocUuid(event.docUuid),
                null,
                event.docUuid.toString(),
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.transport.AcknowledgementReceived.v1"
    }
}
