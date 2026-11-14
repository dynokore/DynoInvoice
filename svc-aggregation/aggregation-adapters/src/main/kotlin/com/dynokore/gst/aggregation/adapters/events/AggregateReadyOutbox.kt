package com.dynokore.gst.aggregation.adapters.events

import com.dynokore.gst.aggregation.application.AggregateReadyPublisher
import com.dynokore.gst.aggregation.application.StoredAggregate
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
 * The adapter of [AggregateReadyPublisher] (INV-REQ-718, channel `aggregationAggregateReady`): one `AggregateReady`
 * per aggregate handed on, appended to the outbox in the transaction the caller has open. The payload is
 * `aggregate-ready-payload.schema.json` of platform-contracts: `aggregateId`, `entityRef`, `aggregateType`, `window`
 * (the period of the aggregate) and `linesByInternalTaxCode` (the stored lines, in order).
 */
class AggregateReadyOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AggregateReadyPublisher {
    override fun ready(aggregate: StoredAggregate) {
        val stored = aggregate.aggregate
        val payload =
            mapper.createObjectNode().also { node ->
                node.put("aggregateId", aggregate.aggregateId.toString())
                node.put("entityRef", stored.entityRef)
                node.put("aggregateType", stored.aggregateType.name)
                node.putObject("window").also {
                    it.put("from", stored.periodStart.toString())
                    it.put("to", stored.periodEnd.toString())
                }
                node.putArray("linesByInternalTaxCode").also { lines ->
                    stored.lines.forEach { line ->
                        lines.addObject().also {
                            it.put("internalTaxCode", line.internalTaxCode)
                            it.put("amount", line.amount)
                        }
                    }
                }
            }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        outbox.append(
            listOf(OutboundEvent(TYPE, mapper.writeValueAsString(payload))),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(stored.entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = aggregate.aggregateId.toString(),
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        /** The type of `AggregationEvents` in the bootstrap (the catalogue entry of the AsyncAPI message). */
        const val TYPE = "com.dynokore.gst.aggregation.AggregateReady.v1"
    }
}
