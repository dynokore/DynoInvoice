package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.gstn.EntityNodeChangedData
import com.dynokore.gst.submission.application.gstn.EntityNodeChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [EntityNodeChangedDataReader] over Jackson, for both structure events. [refMember] is `nodeRef` for
 * OrganisationStructureChanged and `entityRef` for EntityProfileChanged. From `node` it reads `kind`, `parentRef`,
 * `gstn` and `structureVersion` (an integer of at least 1); no `node` gives a null `structureVersion`. Unknown members
 * are ignored (additive-only contract, API-41). A malformed member is a [ContractViolation] naming the member, never
 * its value.
 */
class JacksonEntityNodeChangedDataReader(
    private val refMember: String,
) : EntityNodeChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): EntityNodeChangedData {
        val data = parse(dataJson)
        val ref = data.get(refMember)
        if (ref == null || !ref.isString || ref.asString().isBlank()) {
            throw ContractViolation("structure event data member $refMember is missing or not a non-blank string")
        }
        val node = data.get("node")
        return if (node == null || node.isNull) {
            EntityNodeChangedData(ref.asString(), "", null, null, null)
        } else {
            nodeOf(ref.asString(), node)
        }
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("structure event data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("structure event data is not a JSON object")
        return data
    }

    private fun nodeOf(
        ref: String,
        node: JsonNode,
    ): EntityNodeChangedData {
        if (!node.isObject) throw ContractViolation("structure event member node is not an object")
        return EntityNodeChangedData(
            nodeRef = ref,
            kind = node.requiredString("kind"),
            parentRef = node.optionalString("parentRef"),
            gstn = node.optionalString("gstn"),
            structureVersion = versionOf(node),
        )
    }

    private fun JsonNode.requiredString(name: String): String {
        val member = get(name)
        if (member == null || !member.isString) throw ContractViolation("node member $name is missing or not a string")
        return member.asString()
    }

    private fun JsonNode.optionalString(name: String): String? {
        val member = get(name)
        if (member == null || member.isNull) return null
        if (!member.isString) throw ContractViolation("node member $name is not a string")
        return member.asString()
    }

    private fun versionOf(node: JsonNode): Long {
        val member = node.get("structureVersion")
        val valid = member != null && member.isIntegralNumber && member.canConvertToLong()
        if (!valid || member.asLong() < 1) {
            throw ContractViolation("node member structureVersion is missing or not an integer of at least 1")
        }
        return member.asLong()
    }
}
