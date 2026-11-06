package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.identity.IdentityTripleChangedPublisher
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * TPL-05: maps a confirmed identity triple change to the contract event IdentityTripleChanged
 * (INV-REQ-1135, INV-Q-16401 A) and appends it through outbox-and-queue in the confirmation transaction (CS-17). The
 * aggregate key is the change reference. The payload is the `identity-triple-changed-payload` schema of
 * platform-contracts: the four keys it lists, no triple values.
 */
class IdentityTripleChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : IdentityTripleChangedPublisher {
    override fun publish(change: PendingChange) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(change.version.entity.value),
                docUuid = null,
                transactionDate = null,
                aggregateKey = change.changeRef,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(change).toString())), context)
    }

    internal fun payload(change: PendingChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", change.version.entity.value)
        root.put("constitution", change.version.constitution.code)
        root.put("effectiveFrom", change.version.effectiveFrom.toString())
        root.put("changeRef", change.changeRef)
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "masterdataIdentityTripleChanged"
        const val EVENT_TYPE = "com.dynokore.gst.masterdata.IdentityTripleChanged.v1"
    }
}
