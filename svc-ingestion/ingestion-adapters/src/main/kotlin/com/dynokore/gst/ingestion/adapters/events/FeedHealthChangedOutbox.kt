package com.dynokore.gst.ingestion.adapters.events

import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthChange
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthEvents
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Writes `FeedHealthChanged` (event-carried state transfer, INV-REQ-815) to the outbox of the caller's open
 * transaction, so the state row, its counter and the event commit or vanish together. The payload follows the
 * `FeedHealthChanged` message of the ingestion AsyncAPI contract and always carries `stateVersion`, the producer
 * counter; the tenant travels in the envelope and no user id or role is carried. `errorCode` and `gapDetails` are
 * optional and not sent here. It is a plain class: the composition root binds it.
 */
class FeedHealthChangedOutbox(
    private val outbox: OutboxWriter,
) : FeedHealthEvents {
    override fun publish(change: FeedHealthChange) {
        val key = change.key
        val correlation = UUID.randomUUID().toString()
        outbox.append(
            listOf(OutboundEvent(TYPE, payload(change))),
            EventContext(
                TenantId(key.tenantId),
                EntityRef(key.entityRef),
                null,
                null,
                "feed:${key.feed}:${key.entityRef}:${key.period}:${key.control.name}",
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            ),
        )
    }

    private fun payload(change: FeedHealthChange): String {
        val node = MAPPER.createObjectNode()
        node.put("feed", change.key.feed)
        node.put("entityRef", change.key.entityRef)
        node.put("period", change.key.period)
        node.put("control", change.key.control.name)
        node.put("state", change.state.name)
        node.put("stateVersion", change.stateVersion)
        return MAPPER.writeValueAsString(node)
    }

    companion object {
        const val TYPE = "com.dynokore.gst.ingestion.FeedHealthChanged.v1"
        const val TOPIC = "gst.ingestion.feed-health-changed.v1"
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
