package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.application.exceptions.FailureFacts
import com.dynokore.gst.consoleBff.application.exceptions.PayloadRejectedReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.databind.JsonNode

/** Shared rules of the PayloadRejected and SubmissionFailed readers (INV-AC-21057). */
internal object FailurePayload {
    fun facts(
        data: JsonNode,
        type: String,
        code: String,
        envelopeEntityRef: String?,
        envelopeDocSequence: Long?,
    ): FailureFacts =
        FailureFacts(
            docUuid = docUuid(data, type),
            docsequence = sequence(data, type, envelopeDocSequence),
            entityRef = entityRef(data, type, envelopeEntityRef),
            code = code,
            remediationOwner = ExceptionPayload.optional(data, "remediationOwner", type),
            disposition = ExceptionPayload.optional(data, "disposition", type),
        )

    /** The payload `code` when a non-blank string; null when absent, JSON null or blank; a violation when mistyped. */
    fun code(
        data: JsonNode,
        type: String,
    ): String? {
        val node = data.get("code")
        if (node == null || node.isNull) return null
        if (!node.isString) throw ContractViolation("$type data member code is not a string")
        return node.asString().takeIf { it.isNotBlank() }
    }

    private fun docUuid(
        data: JsonNode,
        type: String,
    ): String {
        val node = data.get("docUuid")
        if (node == null || !node.isString || node.asString().isBlank()) {
            throw ContractViolation("$type data member docUuid is missing or not a non-blank string")
        }
        return node.asString()
    }

    private fun sequence(
        data: JsonNode,
        type: String,
        envelope: Long?,
    ): Long {
        val node = data.get("docsequence")
        if (node == null || node.isNull) {
            return envelope ?: throw ContractViolation("$type has no docsequence in the payload or the envelope")
        }
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
            throw ContractViolation("$type docsequence is not an integer of at least 1")
        }
        return node.asLong()
    }

    private fun entityRef(
        data: JsonNode,
        type: String,
        fallback: String?,
    ): String {
        val node = data.get("entityRef")
        if (node == null || node.isNull) {
            if (fallback == null || fallback.isBlank()) {
                throw ContractViolation("$type has no entityRef in the payload or the envelope")
            }
            return fallback
        }
        if (!node.isString || node.asString().isBlank()) {
            throw ContractViolation("$type data member entityRef is not a non-blank string")
        }
        return node.asString()
    }
}

/** [PayloadRejectedReader] over Jackson (payload-rejected-payload.schema.json). */
class JacksonPayloadRejectedReader : PayloadRejectedReader {
    override fun read(
        dataJson: String,
        envelopeEntityRef: String?,
        envelopeDocSequence: Long?,
    ): FailureFacts {
        val type = "PayloadRejected"
        val data = ExceptionPayload.parse(dataJson, type)
        val code =
            FailurePayload.code(data, type) ?: throw ContractViolation("$type data member code is missing or blank")
        return FailurePayload.facts(data, type, code, envelopeEntityRef, envelopeDocSequence)
    }
}
