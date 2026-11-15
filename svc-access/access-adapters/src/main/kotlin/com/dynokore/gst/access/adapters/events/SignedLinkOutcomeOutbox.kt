package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** The reason codes of the `signed-link-outcome-recorded-payload` schema (INV-Q-17431). */
enum class LinkRefusalReason { MOBILE_NUMBER_REQUIRED, CONSENT_NOT_LIVE, RECIPIENT_INVALID, EXPIRY_INVALID }

/**
 * TPL-05: appends `SignedLinkOutcomeRecorded` through outbox-and-queue, in the transaction the caller holds (CS-17), so
 * the outcome and the link row (or the refusal) commit or roll back together. The payload is the
 * `signed-link-outcome-recorded-payload` schema of platform-contracts: the CloudEvents id of the consumed request, its
 * subject reference, the outcome and, for a refusal, the reason code. Never a link reference, passcode or address. The
 * event is filed under the entity of the request.
 */
class SignedLinkOutcomeOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) {
    /** Appends the outcome of the request [requestEventId]; [reason] is null exactly when the link was issued. */
    fun record(
        requestEventId: String,
        subjectReference: String,
        reason: LinkRefusalReason?,
        under: EntityRef,
        at: Instant,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("requestEventId", requestEventId)
        payload.put("subjectReference", subjectReference)
        payload.put("outcome", if (reason == null) "ISSUED" else "REFUSED")
        reason?.let { payload.put("reasonCode", it.name) }
        payload.put("occurredAt", at.toString())
        outbox.append(
            listOf(OutboundEvent(TYPE, payload.toString())),
            EventContexts.forAggregate(tenants.current(), under, "signed-link-request:$requestEventId"),
        )
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessSignedLinkOutcomeRecorded"
        const val TYPE = "com.dynokore.gst.access.SignedLinkOutcomeRecorded.v1"
    }
}
