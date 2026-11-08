package com.dynokore.gst.tenancy.adapters.certification

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.gst.tenancy.application.certification.ReleaseTrainChanged
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper

/**
 * Transactional outbox (INV-Q-18156 B): when the stored `releaseTrain` fact changes, the event
 * [ConsumedCertificationEvents.MARK_REQUESTED] is appended through the service's own outbox in the transaction that
 * writes the fact, under the reserved platform TenantId. The mark itself runs when the event is delivered
 * ([CertificationMarkRequestedHandler]). The event is internal: it is not a channel of the tenancy contract.
 */
class CertificationMarkRequestedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) : ReleaseTrainChanged {
    override fun changed() {
        val payload = mapper.createObjectNode().put("trigger", StaleTrigger.NEW_TRAIN.name)
        val context = EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, AGGREGATE_KEY)
        outbox.append(listOf(OutboundEvent(ConsumedCertificationEvents.MARK_REQUESTED, payload.toString())), context)
    }

    private companion object {
        const val AGGREGATE_KEY = "installation"
    }
}
