package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.request.SignedLinkRequest
import com.dynokore.gst.masterdata.application.request.SignedLinkRequestedPublisher
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
 * TPL-05: maps a counterparty request to the contract event SignedLinkRequested (INV-REQ-901) and appends it through
 * outbox-and-queue in the transaction that stores the request (CS-17). The aggregate key is the request id, the
 * `subjectReference` of the payload. The payload is the `signed-link-requested-payload` schema: purpose, subject,
 * recipient (`email`, optional `mobileNumber`) and the requested expiry.
 */
class SignedLinkRequestedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : SignedLinkRequestedPublisher {
    override fun publish(
        entity: String,
        request: SignedLinkRequest,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = request.subjectReference,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(request).toString())), context)
    }

    internal fun payload(request: SignedLinkRequest): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("purpose", request.purpose)
        root.put("subjectReference", request.subjectReference)
        val recipient = root.putObject("recipient")
        recipient.put("email", request.email)
        request.mobileNumber?.let { recipient.put("mobileNumber", it) }
        root.put("expiry", request.expiry.toString())
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "masterdataSignedLinkRequested"
        const val EVENT_TYPE = "com.dynokore.gst.masterdata.SignedLinkRequested.v1"
    }
}
