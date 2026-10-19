package com.dynokore.platform.auditlog

import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The `AuditEntryRecorded` payload (platform-contracts
 * `service-runtime/schemas/audit-entry-recorded-payload.schema.json`), shared by [OutboxAuditWriter] and
 * [LocalAuditWriter] so both writers stamp the same data.
 */
internal object AuditPayload {
    /** The platform event type (API-40), schema owned by `service-runtime`. */
    const val EVENT_TYPE = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

    /** @throws IllegalArgumentException when [AuditEntry.priorStateJson] is not a JSON object. */
    fun json(
        entry: AuditEntry,
        mapper: ObjectMapper,
    ): String {
        val node = mapper.createObjectNode()
        node.put("actor", entry.actor)
        node.put("action", entry.action)
        node.put("scope", entry.scope)
        entry.reason?.let { node.put("reason", it) }
        entry.priorStateJson?.let { node.set("priorState", priorState(it, mapper)) }
        return mapper.writeValueAsString(node)
    }

    /** The prior state as a compact JSON object text, or `null`. */
    fun priorState(
        entry: AuditEntry,
        mapper: ObjectMapper,
    ): String? = entry.priorStateJson?.let { mapper.writeValueAsString(priorState(it, mapper)) }

    private fun priorState(
        json: String,
        mapper: ObjectMapper,
    ): ObjectNode {
        val node =
            try {
                mapper.readTree(json)
            } catch (failure: JacksonException) {
                throw IllegalArgumentException("prior state is not JSON", failure)
            }
        require(node is ObjectNode) { "prior state must be a JSON object" }
        return node
    }
}
