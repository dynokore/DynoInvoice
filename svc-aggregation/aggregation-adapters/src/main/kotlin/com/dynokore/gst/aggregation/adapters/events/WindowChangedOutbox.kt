package com.dynokore.gst.aggregation.adapters.events

import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.application.WindowChangePublisher
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The adapter of [WindowChangePublisher] (INV-REQ-413 item 2, channel `aggregationAggregateWindowChanged`): one
 * `AggregateWindowChanged` per part that moves to CLOSED or LATE, appended to the outbox in the transaction the caller
 * has open, which is the one of the close. The payload is `aggregate-window-changed-payload.schema.json` of
 * platform-contracts: `entityRef`, `prescribedPeriod` (the `periodRef` of the span), `window.from`, `window.to`,
 * `state` and, for CLOSED, `aggregateId`. A state OPEN is not a change of an existing window and is refused.
 */
class WindowChangedOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper = ObjectMapper(),
) : WindowChangePublisher {
    override fun changed(change: WindowChange) {
        require(change.state == WindowState.CLOSED || change.state == WindowState.LATE) {
            "only a window part that moves to CLOSED or LATE is published"
        }
        val closed = change.state == WindowState.CLOSED
        val aggregateId = change.aggregateId
        require(!closed || aggregateId != null) { "a CLOSED part carries its aggregate id" }
        val payload =
            mapper.createObjectNode().also { node ->
                node.put("entityRef", change.window.entityRef)
                node.put("prescribedPeriod", change.part.periodRef)
                node.putObject("window").also {
                    it.put("from", change.part.start.toString())
                    it.put("to", change.part.end.toString())
                }
                node.put("state", change.state.name)
                if (closed) node.put("aggregateId", aggregateId.toString())
            }
        outbox.append(
            listOf(OutboundEvent(TYPE, mapper.writeValueAsString(payload))),
            context(change),
        )
    }

    private fun context(change: WindowChange): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = tenants.current(),
            entity = EntityRef(change.window.entityRef),
            docUuid = null,
            transactionDate = null,
            aggregateKey = "window:${change.window.entityRef}:${change.window.aggregateType}:${change.part.start}",
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }

    companion object {
        /** The type of `AggregationEvents` in the bootstrap (the catalogue entry of the AsyncAPI message). */
        const val TYPE = "com.dynokore.gst.aggregation.AggregateWindowChanged.v1"
    }
}
