package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.entity.EntityConstitutionFact
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reads the entity constitution of an `EntityProfileChanged` event (INV-REQ-479, ruling INV-Q-21383 A: event-carried
 * state transfer). The tenant comes only from the envelope `tenantid` (CloudEvents attribute), never from the data.
 *
 * `constitution` absent or null: no fact (an older event without the additive member; a normal event). Anything else
 * that is not usable fails closed with [ContractViolation] (dead letter): a constitution that is not a non-blank text,
 * a missing or non-integer `node.structureVersion` or one below 1 (the ordering version), a missing `entityRef`, or an
 * envelope without a tenant (the platform tenant stands for a missing `tenantid`).
 */
class EntityConstitutionReader {
    private val json = JsonMapper.builder().build()

    /** The fact of the event, or null when the event carries no constitution. */
    fun read(
        dataJson: String,
        tenant: TenantId,
    ): EntityConstitutionFact? {
        val data = parse(dataJson)
        val constitution = data.get(CONSTITUTION)?.takeIf { !it.isNull } ?: return null
        if (!constitution.isString || constitution.asString().isBlank()) {
            throw ContractViolation("$CONSTITUTION is not a non-blank text")
        }
        if (tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val entityRef =
            data.get(ENTITY_REF)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
                ?: throw ContractViolation("$ENTITY_REF is missing or not a non-blank text")
        val version =
            data
                .get(NODE)
                ?.get(STRUCTURE_VERSION)
                ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
                ?.asLong()
                ?.takeIf { it >= 1 }
                ?: throw ContractViolation("$NODE.$STRUCTURE_VERSION is missing, not an integer or below 1")
        return EntityConstitutionFact(tenant.value, entityRef, constitution.asString(), version)
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val CONSTITUTION = "constitution"
        const val ENTITY_REF = "entityRef"
        const val NODE = "node"
        const val STRUCTURE_VERSION = "structureVersion"
    }
}
