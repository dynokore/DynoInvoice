package com.dynokore.gst.aggregation.adapters.events

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
 * Appends `ShortCodeUsed` (channel `aggregationShortCodeUsed`, INV-Q-11266 A) to the outbox in the transaction the
 * caller has open. The payload is `short-code-used-payload.schema.json`: `code`, `nodeKind`, `nodeRef`, `aggregateId`
 * and `documentNumber`. The emission guard of the outbox refuses a type the catalogue does not list for aggregation-svc.
 */
class ShortCodeUsedOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    @Suppress("LongParameterList")
    fun used(
        entityRef: String,
        code: String,
        nodeKind: String,
        nodeRef: String,
        aggregateId: UUID,
        documentNumber: String,
    ) {
        val payload =
            mapper.createObjectNode().also {
                it.put("code", code)
                it.put("nodeKind", nodeKind)
                it.put("nodeRef", nodeRef)
                it.put("aggregateId", aggregateId.toString())
                it.put("documentNumber", documentNumber)
            }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        outbox.append(
            listOf(OutboundEvent(TYPE, mapper.writeValueAsString(payload))),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = aggregateId.toString(),
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        /** The type of `AggregationEvents` in the bootstrap (the catalogue entry of the AsyncAPI message). */
        const val TYPE = "com.dynokore.gst.aggregation.ShortCodeUsed.v1"
    }
}
