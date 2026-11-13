package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.register.DocumentReleasedData
import com.dynokore.gst.submission.application.register.DocumentReleasedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [DocumentReleasedDataReader] over Jackson (document-released-payload.schema.json): reads `docUuid` and `docsequence`
 * (an integer of at least 1) by exact member name; `releasedHoldReasons` must be an array; unknown members are
 * ignored. A missing or malformed member is a [ContractViolation] that names the member, never its value.
 */
class JacksonDocumentReleasedDataReader : DocumentReleasedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): DocumentReleasedData {
        val data = parse(dataJson)
        val released = data.get("releasedHoldReasons")
        if (released == null || !released.isArray) {
            throw ContractViolation("DocumentReleased data member releasedHoldReasons is missing or not an array")
        }
        return DocumentReleasedData(docUuid = data.text("docUuid"), docsequence = sequenceOf(data))
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("DocumentReleased data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("DocumentReleased data is not a JSON object")
        return data
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("DocumentReleased data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun sequenceOf(data: JsonNode): Long {
        val node = data.get("docsequence")
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong()
        if (!valid || node.asLong() < 1) {
            throw ContractViolation("DocumentReleased docsequence is missing or not an integer of at least 1")
        }
        return node.asLong()
    }
}
