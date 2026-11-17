package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.AcknowledgementDataReader
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedData
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [AcknowledgementDataReader] over Jackson. `docUuid`, `version` and `acknowledgementId` are required; an absent
 * `outcome` reads as ACCEPTED and a value other than ACCEPTED or REJECTED throws. Every malformed member is a
 * [ContractViolation] naming the member, never its value (the record goes to the dead letter at once); unknown
 * members are ignored (additive contract, API-41).
 */
class JacksonAcknowledgementReceivedDataReader : AcknowledgementDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): AcknowledgementReceivedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AcknowledgementReceived data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("AcknowledgementReceived data is not a JSON object")
        }
        return AcknowledgementReceivedData(
            docUuid = text(data, "docUuid"),
            version = versionOf(data),
            acknowledgementId = text(data, "acknowledgementId"),
            outcome = outcomeOf(data),
        )
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString || node.asString().isBlank()) {
            throw ContractViolation("AcknowledgementReceived member $name is missing or not a non-blank string")
        }
        return node.asString()
    }

    private fun versionOf(data: JsonNode): Int {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) {
            throw ContractViolation("AcknowledgementReceived member version is missing or not an integer of at least 1")
        }
        return node.asInt()
    }

    private fun outcomeOf(data: JsonNode): String {
        val node = data.get("outcome") ?: return AcknowledgementReceivedData.OUTCOME_ACCEPTED
        val text = if (node.isString) node.asString() else null
        val known = listOf(AcknowledgementReceivedData.OUTCOME_ACCEPTED, AcknowledgementReceivedData.OUTCOME_REJECTED)
        if (text == null || text !in known) {
            throw ContractViolation("AcknowledgementReceived member outcome is not ACCEPTED or REJECTED")
        }
        return text
    }
}
