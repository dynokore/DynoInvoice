package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.adapters.persistence.EntityIdentity
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Reads the optional `entityId` of an `EntityProfileChanged` event (INV-REQ-479, ruling INV-Q-21451 A). The tenant
 * comes only from the envelope `tenantid`, never from the data.
 *
 * No fact (null, a normal event): the node is not an `ENTITY` node, or `entityId` is absent. Fails closed with
 * [ContractViolation] (dead letter) once `entityId` is present and: it is not a canonical lower-case uuid text
 * (JSON null included), `entityRef` is missing, `node.structureVersion` is missing, not an integer or below 1, or the
 * envelope has no tenant (the platform tenant stands for a missing `tenantid`).
 */
class EntityIdentityReader {
    private val json = JsonMapper.builder().build()

    /** The identity of the event, or null when it carries none. */
    fun read(
        dataJson: String,
        tenant: TenantId,
    ): EntityIdentity? {
        val data = parse(dataJson)
        val node = data.get(NODE)?.takeIf { it.get(KIND)?.takeIf { kind -> kind.isString }?.asString() == ENTITY }
        val id = data.get(ENTITY_ID)
        if (node == null || id == null) return null
        if (tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        return EntityIdentity(tenant.value, entityRef(data), entityId(id), version(node))
    }

    private fun entityId(id: JsonNode): UUID {
        val text = id.takeIf { it.isString }?.asString()
        val parsed =
            try {
                text?.let { UUID.fromString(it) }
            } catch (e: IllegalArgumentException) {
                throw ContractViolation("$ENTITY_ID is not a uuid", e)
            }
        if (parsed == null || parsed.toString() != text) throw ContractViolation("$ENTITY_ID is not a canonical uuid text")
        return parsed
    }

    private fun entityRef(data: JsonNode): String =
        data.get(ENTITY_REF)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$ENTITY_REF is missing or not a non-blank text")

    private fun version(node: JsonNode): Long =
        node
            .get(STRUCTURE_VERSION)
            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.asLong()
            ?.takeIf { it >= 1 }
            ?: throw ContractViolation("$NODE.$STRUCTURE_VERSION is missing, not an integer or below 1")

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val ENTITY = "ENTITY"
        const val ENTITY_REF = "entityRef"
        const val ENTITY_ID = "entityId"
        const val NODE = "node"
        const val KIND = "kind"
        const val STRUCTURE_VERSION = "structureVersion"
    }
}
