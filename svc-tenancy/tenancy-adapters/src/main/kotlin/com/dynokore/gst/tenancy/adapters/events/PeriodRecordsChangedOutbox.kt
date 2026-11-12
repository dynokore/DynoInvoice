package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.period.PeriodRecordsChange
import com.dynokore.gst.tenancy.application.period.PeriodRecordsChangePublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: maps a change of an entity's period records to the contract event PeriodRecordsChanged and appends it through
 * outbox-and-queue, in the transaction of the command that changed the records (CS-17; INV-Q-16535 A, event-carried
 * state transfer). The payload is the `period-records-changed-payload` schema of platform-contracts: `entityRef`, the
 * monotonic `version` (consumers apply it idempotently by version) and the `periods` in force, each with `periodRef`,
 * `periodStart`, `periodEnd` and `filingDueDate` as ISO dates, in the order given.
 */
class PeriodRecordsChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : PeriodRecordsChangePublisher {
    override fun publish(change: PeriodRecordsChange) {
        val context =
            EventContexts.forAggregate(
                tenants.current(),
                EntityRef(change.entityRef),
                change.entityRef,
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(change).toString())), context)
    }

    internal fun payload(change: PeriodRecordsChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", change.entityRef)
        root.put("version", change.version)
        val periods = root.putArray("periods")
        change.periods.forEach {
            periods
                .addObject()
                .put("periodRef", it.periodRef)
                .put("periodStart", it.periodStart.toString())
                .put("periodEnd", it.periodEnd.toString())
                .put("filingDueDate", it.filingDueDate.toString())
        }
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "PeriodRecordsChanged"

        /** The message `name` of the channel in the tenancy asyncapi document. */
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    }
}
