package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.ExpiryNoticeMessages
import com.dynokore.gst.access.application.grant.ExpiryNoticeRequest
import com.dynokore.gst.access.application.link.LinkMessages
import com.dynokore.gst.access.application.link.MessageRequest
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: appends `MessageRequested` through outbox-and-queue, in the transaction the caller holds (CS-17), so a
 * rolled-back transaction publishes nothing. The payload is the `message-requested-payload` schema of
 * platform-contracts (`template`, `recipients`, `channel`, and `linkReference` or `sealedPasscode`). A message
 * concerns an entity, as
 * the outbox refuses a domain event under the tenant-wide entity (FR-K.3): the caller names it with [filedUnder], and a
 * request outside that scope is refused.
 */
class OutboxLinkMessages(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : LinkMessages,
    ExpiryNoticeMessages {
    private val signedLinkEvents = SignedLinkEventOutbox(outbox, tenants, mapper)

    private val outcomes = SignedLinkOutcomeOutbox(outbox, tenants, mapper)

    private val entity = ThreadLocal<EntityRef?>()

    /** Runs [block] with every message it requests filed under [under]; the scope ends with the call. */
    fun <T> filedUnder(
        under: EntityRef,
        block: () -> T,
    ): T {
        val outer = entity.get()
        entity.set(under)
        try {
            return block()
        } finally {
            if (outer == null) entity.remove() else entity.set(outer)
        }
    }

    /** `SignedLinkIssued` in the transaction that stores the link (accessSignedLinkIssued). */
    override fun issued(link: SignedLink) = signedLinkEvents.issued(link)

    /** `SignedLinkOutcomeRecorded` for the request [requestEventId], in the transaction of the caller. */
    fun outcome(
        requestEventId: String,
        subjectReference: String,
        reason: LinkRefusalReason?,
        under: EntityRef,
        at: java.time.Instant,
    ) = outcomes.record(requestEventId, subjectReference, reason, under, at)

    override fun request(message: MessageRequest) {
        val under = checkNotNull(entity.get()) { "a message is requested inside filedUnder, under an entity" }
        append(message.template, listOf(message.recipient), message.channel.name, under) {
            message.linkReference?.let { put("linkReference", it) }
            message.sealedPasscode?.let { put("sealedPasscode", it) }
        }
    }

    /** An expiry notice: `template`, `recipients` and `channel` only, filed under the entity of the request. */
    override fun request(request: ExpiryNoticeRequest) =
        append(request.template, request.recipients, request.channel.name, EntityRef(request.entity.value)) {}

    /** The one place that appends `MessageRequested`, in the transaction the caller holds. */
    private fun append(
        template: String,
        recipients: List<String>,
        channel: String,
        under: EntityRef,
        extra: tools.jackson.databind.node.ObjectNode.() -> Unit,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("template", template)
        val to = payload.putArray("recipients")
        recipients.forEach { to.add(it) }
        payload.put("channel", channel)
        payload.extra()
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload.toString())),
            EventContexts.forAggregate(tenants.current(), under, "entity:${under.value}"),
        )
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessMessageRequested"
        const val EVENT_TYPE = "com.dynokore.gst.access.MessageRequested.v1"
    }
}
