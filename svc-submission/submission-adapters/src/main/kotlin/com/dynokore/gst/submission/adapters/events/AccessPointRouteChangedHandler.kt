package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.adapters.persistence.JooqApConnectionEntities
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Keeps the replica of the connection each entity is assigned to from `AccessPointRouteChanged` (INV-AC-23662). It
 * reads `connectionRef`, `entityRefs` and `version` (an integer of at least 1); unknown members are ignored (additive
 * contract, API-41). A malformed member is a [ContractViolation] naming the member, never its value, and stores
 * nothing. A stale or repeated version of the same connection is IGNORED.
 */
class AccessPointRouteChangedHandler(
    private val tenants: TenantContext,
    private val connections: JooqApConnectionEntities,
) : EventHandler {
    private val mapper = ObjectMapper()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val connectionRef = ref(data.get("connectionRef"), "connectionRef")
        val entities = data.get("entityRefs")
        if (entities == null || !entities.isArray) {
            throw ContractViolation("member entityRefs is missing or not an array")
        }
        val refs = entities.values().map { ref(it, "entityRefs item") }
        val version = versionOf(data.get("version"))
        return if (connections.apply(tenants.current(), connectionRef, refs, version)) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AccessPointRouteChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("AccessPointRouteChanged data is not a JSON object")
        return data
    }

    private fun versionOf(node: JsonNode?): Long {
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong()
        if (!valid || node.asLong() < 1) {
            throw ContractViolation("member version is missing or not an integer of at least 1")
        }
        return node.asLong()
    }

    private fun ref(
        node: JsonNode?,
        name: String,
    ): String {
        val text = if (node != null && node.isString) node.asString() else ""
        if (text.isBlank() || text.length > MAX_REF) {
            throw ContractViolation("member $name is missing or not a string of 1 to $MAX_REF characters")
        }
        return text
    }

    private companion object {
        const val MAX_REF = 40
    }
}
