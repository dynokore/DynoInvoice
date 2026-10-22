package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * TPL-05: maps a written counterparty version to the contract event MasterDataResolved (INV-REQ-815) and appends it
 * through outbox-and-queue, in the command transaction the caller holds (CS-17). The envelope's tenant is the bound
 * tenant (the token's) and its entity is the entity of the request; the aggregate key is the party reference and the
 * aggregate version the counterparty version. The payload is the `master-data-resolved-payload` schema of
 * platform-contracts, with the provenance of each identifier added (INV-REQ-314 behavior 2; the schema allows extra
 * properties). The emission guard of outbox-and-queue refuses the type unless the catalogue lists masterdata-svc as
 * its producer.
 */
class MasterDataResolvedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : MasterDataResolvedPublisher {
    override fun publish(
        entity: String,
        version: CounterpartyVersion,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = version.partyRef.value,
                aggregateVersion = version.version,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(version).toString())), context)
    }

    internal fun payload(version: CounterpartyVersion): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("partyRef", version.partyRef.value)
        val identifiers = root.putArray("resolvedIdentifiers")
        version.content.identifiers.forEach {
            val node = identifiers.addObject()
            node.put("scheme", it.scheme.name)
            node.put("value", it.value)
            node.put("providedBy", it.provenance.providedBy.name)
            node.put("evidenceRef", it.provenance.evidenceRef.value)
            node.put("capturedAt", ISO.format(it.provenance.capturedAt.atOffset(ZoneOffset.UTC)))
        }
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "masterdataMasterDataResolved"
        const val EVENT_TYPE = "com.dynokore.gst.masterdata.MasterDataResolved.v1"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
