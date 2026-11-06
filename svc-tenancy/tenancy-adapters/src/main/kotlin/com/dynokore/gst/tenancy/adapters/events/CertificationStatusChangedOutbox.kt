package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.certification.CertificationStatusPublisher
import com.dynokore.gst.tenancy.domain.CertificationStatus
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * TPL-05: maps a transition of the certification status to the contract event CertificationStatusChanged
 * (INV-T-16428, INV-Q-16401 A) and appends it through outbox-and-queue, in the transaction of the mark or the passing
 * record that makes it (CS-17). The aggregate key is `entityRef`. The payload is the
 * `certification-status-changed-payload` schema: `entityRef`, `status`, `changedAt` and `trigger` only when STALE.
 */
class CertificationStatusChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
    private val clock: () -> Instant,
) : CertificationStatusPublisher {
    override fun publish(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    ) {
        val context = EventContexts.forAggregate(tenants.current(), EntityRef(entityRef), entityRef)
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(entityRef, status, trigger).toString())), context)
    }

    internal fun payload(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", entityRef)
        root.put("status", status.name)
        root.put("changedAt", clock().toString())
        if (status == CertificationStatus.STALE) root.put("trigger", checkNotNull(trigger) { "STALE needs a trigger" }.name)
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "CertificationStatusChanged"

        /** The message `name` of the channel in the tenancy asyncapi document. */
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.CertificationStatusChanged.v1"
    }
}
