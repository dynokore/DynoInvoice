package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.rate.ReferenceRateLoaded
import com.dynokore.gst.masterdata.application.rate.ReferenceRateLoadedPublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The entity a rate import runs for, bound by the `importRates` controller for the duration of one request. The
 * application event carries no entity, but the outbox envelope requires one (TPL-05), so the controller binds it here.
 */
class RateImportEntity {
    private val bound = ThreadLocal<String?>()

    fun <T> within(
        entity: String,
        block: () -> T,
    ): T {
        val before = bound.get()
        bound.set(entity)
        try {
            return block()
        } finally {
            if (before == null) bound.remove() else bound.set(before)
        }
    }

    fun current(): String = checkNotNull(bound.get()) { "no entity is bound to this rate import" }
}

/**
 * TPL-05: maps a load that stored records to the contract event ReferenceRateLoaded (INV-REQ-506) and appends it
 * through outbox-and-queue. The use case requests it once per load that stored at least one record, after its commit,
 * so the append runs in its own transaction. The aggregate key is the import id. The payload is the
 * `reference-rate-loaded-payload` schema: source, currency pairs and the date range.
 */
class ReferenceRateLoadedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val entity: RateImportEntity,
) : ReferenceRateLoadedPublisher {
    override fun publish(event: ReferenceRateLoaded) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity.current()),
                docUuid = null,
                transactionDate = null,
                aggregateKey = event.importId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        tx.inTransaction { outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(event).toString())), context) }
    }

    internal fun payload(event: ReferenceRateLoaded): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("source", event.source)
        val pairs = root.putArray("currencyPairs")
        event.currencyPairs.forEach { pairs.add(it) }
        val range = root.putObject("dateRange")
        range.put("from", event.fromDate.toString())
        range.put("to", event.toDate.toString())
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "masterdataReferenceRateLoaded"
        const val EVENT_TYPE = "com.dynokore.gst.masterdata.ReferenceRateLoaded.v1"
    }
}
