package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.LinkMessages
import com.dynokore.gst.access.application.link.MessageRequest
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
) : LinkMessages {
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

    override fun request(message: MessageRequest) {
        val under = checkNotNull(entity.get()) { "a message is requested inside filedUnder, under an entity" }
        val payload = mapper.createObjectNode()
        payload.put("template", message.template)
        payload.putArray("recipients").add(message.recipient)
        payload.put("channel", message.channel.name)
        message.linkReference?.let { payload.put("linkReference", it) }
        message.sealedPasscode?.let { payload.put("sealedPasscode", it) }
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
