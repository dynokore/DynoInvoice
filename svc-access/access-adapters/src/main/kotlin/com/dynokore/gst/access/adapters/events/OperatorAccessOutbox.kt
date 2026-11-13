package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.application.operatorsession.OperatorAccessPublisher
import com.dynokore.gst.access.application.operatorsession.OperatorAccessRecord
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: appends the operator access record of a session as the platform event AuthorityChanged with changeKind
 * OPERATOR_ACCESS (INV-Q-16496 A), in the transaction the caller holds. The envelope carries the chosen tenant and the
 * tenant-wide entity, keyed on the operator's user ID.
 */
class OperatorAccessOutbox(
    private val outbox: OutboxWriter,
    mapper: ObjectMapper,
    incarnations: AuthorityIncarnationStore,
) : OperatorAccessPublisher {
    private val payloads = AuthorityChangedPayloads(mapper, incarnations)

    override fun publish(record: OperatorAccessRecord) {
        val context = EventContexts.forAggregate(record.tenant, EntityRef.TENANT_WIDE, record.operatorRef)
        outbox.append(
            listOf(OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, payload(record).toString())),
            context,
        )
    }

    internal fun payload(record: OperatorAccessRecord): ObjectNode = payloads.operatorAccess(record)
}
