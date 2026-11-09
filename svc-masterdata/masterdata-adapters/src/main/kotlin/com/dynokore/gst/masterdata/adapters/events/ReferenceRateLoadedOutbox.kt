package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.rate.RateImportStore
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
    private val imports: RateImportStore,
) : ReferenceRateLoadedPublisher {
    override fun publish(event: ReferenceRateLoaded) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entityOf(event.importId)),
                docUuid = null,
                transactionDate = null,
                aggregateKey = event.importId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        tx.inTransaction { outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(event).toString())), context) }
    }

    /** The load runs on the long-command executor, so the entity is read from the import's own record. */
    private fun entityOf(importId: String): String =
        checkNotNull(tx.inTransaction { imports.find(importId) }) { "no rate import record $importId" }.entity

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
