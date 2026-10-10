package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * [AuditWriter] over outbox-and-queue's [OutboxWriter]: one `AuditEntryRecorded` row in the service's own
 * outbox, on the connection of the caller's open transaction (INV-REQ-1226, SB-05). Any failure of the
 * outbox write surfaces as [AuditWriteFailed].
 */
class OutboxAuditWriter(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AuditWriter {
    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val event = OutboundEvent(EVENT_TYPE, payload(entry))
        return try {
            outbox.append(listOf(event), context).single()
        } catch (failure: Exception) { // includes the checked SQLException of a refused insert
            throw AuditWriteFailed(failure)
        }
    }

    private fun payload(entry: AuditEntry): String {
        val node = mapper.createObjectNode()
        node.put("actor", entry.actor)
        node.put("action", entry.action)
        node.put("scope", entry.scope)
        entry.reason?.let { node.put("reason", it) }
        entry.priorStateJson?.let { node.set("priorState", parseObject(it)) }
        return mapper.writeValueAsString(node)
    }

    private fun parseObject(json: String): ObjectNode {
        val node =
            try {
                mapper.readTree(json)
            } catch (failure: JacksonException) {
                throw IllegalArgumentException("prior state is not JSON", failure)
            }
        require(node is ObjectNode) { "prior state must be a JSON object" }
        return node
    }

    companion object {
        /** The platform event type (API-40), schema owned by `service-runtime`. */
        const val EVENT_TYPE = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
    }
}
