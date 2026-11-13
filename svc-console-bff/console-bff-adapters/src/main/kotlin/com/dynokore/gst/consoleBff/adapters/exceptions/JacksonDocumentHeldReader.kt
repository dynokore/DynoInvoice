package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldReader
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Shared parsing helpers of the two exception payload readers. */
internal object ExceptionPayload {
    private val mapper = ObjectMapper()

    fun parse(
        dataJson: String,
        type: String,
    ): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("$type data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("$type data is not a JSON object")
        return data
    }

    fun required(
        data: JsonNode,
        name: String,
        type: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("$type data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    fun optional(
        data: JsonNode,
        name: String,
        type: String,
    ): String {
        val node = data.get(name)
        if (node == null || node.isNull) return ""
        if (!node.isString) throw ContractViolation("$type data member $name is not a string")
        return node.asString()
    }

    fun sequence(
        data: JsonNode,
        type: String,
    ): Long {
        val node = data.get("docsequence")
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong()
        if (!valid || node.asLong() < 1) {
            throw ContractViolation("$type docsequence is missing or not an integer of at least 1")
        }
        return node.asLong()
    }

    fun reasons(
        data: JsonNode,
        name: String,
        type: String,
    ): List<String> {
        val node = data.get(name)
        if (node == null || !node.isArray || node.size() == 0) {
            throw ContractViolation("$type data member $name is missing or not a non-empty array")
        }
        return node.values().map {
            if (!it.isString) throw ContractViolation("$type data member $name holds a non-string")
            it.asString()
        }
    }
}

/** [DocumentHeldReader] over Jackson (document-held-payload.schema.json). */
class JacksonDocumentHeldReader : DocumentHeldReader {
    override fun read(
        dataJson: String,
        envelopeEntityRef: String?,
    ): DocumentHeldFacts {
        val type = "DocumentHeld"
        val data = ExceptionPayload.parse(dataJson, type)
        return DocumentHeldFacts(
            entityRef = entityRefOf(data, envelopeEntityRef),
            docUuid = ExceptionPayload.required(data, "docUuid", type),
            docsequence = ExceptionPayload.sequence(data, type),
            holdReasons = ExceptionPayload.reasons(data, "holdReasons", type),
            prescribedPeriodRef = ExceptionPayload.optional(data, "prescribedPeriodRef", type),
            code = ExceptionPayload.optional(data, "code", type),
            remediationOwner = ExceptionPayload.optional(data, "remediationOwner", type),
            disposition = ExceptionPayload.optional(data, "disposition", type),
        )
    }

    private fun entityRefOf(
        data: JsonNode,
        fallback: String?,
    ): String {
        val node = data.get("entityRef")
        if (node == null || node.isNull) {
            if (fallback == null || fallback.isBlank()) {
                throw ContractViolation("DocumentHeld has no entityRef in the payload or the envelope")
            }
            return fallback
        }
        if (!node.isString || node.asString().isBlank()) {
            throw ContractViolation("DocumentHeld data member entityRef is not a non-blank string")
        }
        return node.asString()
    }
}
