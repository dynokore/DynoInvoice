package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.PayloadReadyData
import com.dynokore.gst.submission.application.queue.PayloadReadyDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID

/**
 * [PayloadReadyDataReader] over Jackson (payload-ready-payload.schema.json). Every malformed member is a
 * [ContractViolation]: the handler's failure is then final and the record goes to the dead letter at once.
 */
class JacksonPayloadReadyDataReader : PayloadReadyDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): PayloadReadyData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("PayloadReady data is not JSON", e)
            }
        return PayloadReadyData(
            docUuid = uuidOf(data.text("docUuid")),
            version = versionOf(data),
            submissionType = data.text("submissionType"),
            specificationVersion = data.text("specificationVersion"),
            payloadBytes = decode(data.text("payloadBytes")),
            payloadSha256 = data.text("payloadSha256"),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("PayloadReady data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun uuidOf(text: String): String =
        try {
            val canonical = UUID.fromString(text).toString()
            require(canonical.equals(text, ignoreCase = true)) { "not canonical" }
            canonical
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("PayloadReady docUuid is not a UUID", e)
        }

    private fun versionOf(data: JsonNode): Int {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) {
            throw ContractViolation("PayloadReady version is missing or not an integer of at least 1")
        }
        return node.asInt()
    }

    private fun decode(text: String): ByteArray =
        try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("PayloadReady payloadBytes is not base64", e)
        }
}
