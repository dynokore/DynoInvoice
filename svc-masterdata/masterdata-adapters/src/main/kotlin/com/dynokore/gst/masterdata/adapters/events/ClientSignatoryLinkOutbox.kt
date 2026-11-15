package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatoryLinkPublisher
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatoryLinkRequest
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
 * TPL-05: maps a recorded client signatory to the contract event SignedLinkRequested with `linkKind`
 * clientSignatory (INV-REQ-899, INV-T-17441) and appends it through outbox-and-queue in the transaction that stores
 * the record (CS-17). The entity is the client; the aggregate key is the signatory reference. Returns the CloudEvents
 * id of the event, which the record keeps so that the outcome event can find it.
 */
class ClientSignatoryLinkOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : ClientSignatoryLinkPublisher {
    override fun publish(request: ClientSignatoryLinkRequest): String {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(request.clientRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = request.signatoryRef,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        val written =
            outbox.append(
                listOf(OutboundEvent(SignedLinkRequestedOutbox.EVENT_TYPE, payload(request).toString())),
                context,
            )
        return written.single().id.toString()
    }

    internal fun payload(request: ClientSignatoryLinkRequest): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("purpose", request.purpose)
        root.put("subjectReference", request.subjectReference)
        val recipient = root.putObject("recipient")
        recipient.put("email", request.email)
        request.mobileNumber?.let { recipient.put("mobileNumber", it) }
        root.put("expiry", request.expiry.toString())
        root.put("linkKind", LINK_KIND)
        root.put("clientRef", request.clientRef)
        root.put("consentRef", request.consentRef)
        root.put("signatoryRef", request.signatoryRef)
        root.put("channel", request.channel)
        root.put("sourceRecordId", request.sourceRecordId)
        return root
    }

    companion object {
        const val LINK_KIND = "clientSignatory"
    }
}
