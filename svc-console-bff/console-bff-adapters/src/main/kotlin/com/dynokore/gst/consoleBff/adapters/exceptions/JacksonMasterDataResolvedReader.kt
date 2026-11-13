package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.application.exceptions.MasterDataResolvedReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.databind.JsonNode

/** [MasterDataResolvedReader] over Jackson; entries are not parsed as uuids (the contract validator owns that). */
class JacksonMasterDataResolvedReader : MasterDataResolvedReader {
    override fun read(dataJson: String): List<String> {
        val data = ExceptionPayload.parse(dataJson, TYPE)
        for (name in listOf("partyRef", "resolvedIdentifiers")) {
            if (!data.has(name) || data.get(name).isNull) throw ContractViolation("$TYPE data member $name is missing")
        }
        val node = data.get("docUuids")
        return if (node == null || node.isNull) emptyList() else entries(node)
    }

    private fun entries(node: JsonNode): List<String> {
        val valid = node.isArray && node.all { it.isString && it.asString().isNotBlank() }
        if (!valid) throw ContractViolation("$TYPE data member docUuids is not an array of non-blank strings")
        return node.asIterable().map { it.asString() }.distinct()
    }

    private companion object {
        const val TYPE = "MasterDataResolved"
    }
}
