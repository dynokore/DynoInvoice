package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.period.FilingFrequencyChange
import com.dynokore.gst.tenancy.application.period.FilingFrequencyChangePublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: maps a confirmed filing-frequency change to the contract event FilingFrequencyChanged and appends it through
 * outbox-and-queue, in the transaction of `confirmPeriods` that applies it (CS-17). The payload is the
 * `filing-frequency-changed-payload` schema of platform-contracts: `entityRef`, `oldFrequency`, `newFrequency` and the
 * open-ended `affectedSpan` (`fromPeriod`, the periodRef of the stored record that starts on the effective date; as the schema describes `toPeriod` absent when open-ended).
 */
class FilingFrequencyChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : FilingFrequencyChangePublisher {
    override fun publish(change: FilingFrequencyChange) {
        val context =
            EventContexts.forAggregate(
                tenants.current(),
                EntityRef(change.entityRef),
                change.entityRef,
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(change).toString())), context)
    }

    internal fun payload(change: FilingFrequencyChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", change.entityRef)
        root.put("oldFrequency", change.oldFrequency)
        root.put("newFrequency", change.newFrequency)
        root.putObject("affectedSpan").put("fromPeriod", change.fromPeriod)
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "FilingFrequencyChanged"

        /** The message `name` of the channel in the tenancy asyncapi document. */
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.FilingFrequencyChanged.v1"
    }
}
