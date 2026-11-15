package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.adapters.persistence.ProfileElectionEntry
import com.dynokore.gst.canonical.adapters.persistence.ProfileElectionSet
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads the purchase date basis and GST scheme entries of an `EntityProfileChanged` event (INV-REQ-479, ruling
 * INV-Q-21422 A: event-carried state transfer). The tenant comes only from the envelope `tenantid` (CloudEvents
 * attribute), never from the data.
 *
 * No fact (null, a normal event): the node is not an `ENTITY` node, or no `node.profile` item names an election
 * attribute (`purchaseDateBasis` or `gstScheme.<KEY>`). Any other attribute (for example `issuanceMode`) is dropped.
 * Fails closed with [ContractViolation] (dead letter) once an election entry is present and: a `purchaseDateBasis`
 * value other than INVOICE_DATE and POSTING_DATE, a malformed scheme key or a value that is not a text of 1 to 40
 * characters, an `effectiveDate` that is missing or does not parse, a missing or non-integer `node.structureVersion`
 * or one below 1, a missing `entityRef`, or an envelope without a tenant (the platform tenant stands for a missing
 * `tenantid`).
 */
class ProfileElectionReader {
    private val json = JsonMapper.builder().build()

    /** The set of the event, or null when the event carries no election entry of an entity node. */
    fun read(
        dataJson: String,
        tenant: TenantId,
    ): ProfileElectionSet? {
        val data = parse(dataJson)
        val node = data.get(NODE)?.takeIf { it.get(KIND)?.takeIf { kind -> kind.isString }?.asString() == ENTITY }
        val items =
            node
                ?.get(PROFILE)
                ?.takeIf { it.isArray }
                ?.filter { isElection(it) }
                .orEmpty()
        if (node == null || items.isEmpty()) return null
        if (tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        return ProfileElectionSet(tenant.value, entityRef(data), version(node), items.map { entry(it) })
    }

    private fun isElection(item: JsonNode): Boolean {
        val attribute = item.get(ATTRIBUTE)?.takeIf { it.isString }?.asString() ?: return false
        return attribute == BASIS || attribute.startsWith(SCHEME_PREFIX)
    }

    private fun entry(item: JsonNode): ProfileElectionEntry {
        val attribute = item.get(ATTRIBUTE).asString()
        val value =
            item
                .get(VALUE)
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf { it.length in 1..MAX_VALUE }
        val valid =
            value != null &&
                if (attribute == BASIS) value in BASES else SCHEME_KEY.matches(attribute)
        if (!valid) throw ContractViolation("$PROFILE item $attribute has no usable attribute or value")
        return ProfileElectionEntry(attribute, checkNotNull(value), effectiveDate(item, attribute))
    }

    private fun effectiveDate(
        item: JsonNode,
        attribute: String,
    ): LocalDate {
        val text =
            item.get(EFFECTIVE_DATE)?.takeIf { it.isString }?.asString()
                ?: throw ContractViolation("$PROFILE item $attribute has no effectiveDate")
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$PROFILE item $attribute effectiveDate does not parse", e)
        }
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
        const val BASIS = "purchaseDateBasis"
        const val SCHEME_PREFIX = "gstScheme."
        val SCHEME_KEY = Regex("^gstScheme\\.[A-Z0-9_-]{1,40}$")
        val BASES = setOf("INVOICE_DATE", "POSTING_DATE")
        const val MAX_VALUE = 40
        const val ENTITY = "ENTITY"
        const val ENTITY_REF = "entityRef"
        const val NODE = "node"
        const val KIND = "kind"
        const val PROFILE = "profile"
        const val ATTRIBUTE = "attribute"
        const val VALUE = "value"
        const val EFFECTIVE_DATE = "effectiveDate"
        const val STRUCTURE_VERSION = "structureVersion"
    }
}
