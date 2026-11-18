package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementData
import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementReader
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [RejectedAcknowledgementReader] over Jackson. `docUuid`, `version` and `reasons` are read; each reason needs a
 * non-blank `code` and `message`, and a missing member throws a [ContractViolation] naming the member, never its
 * value. The order of `reasons` is kept. Unknown members are ignored (additive contract, API-41).
 */
class JacksonRejectedAcknowledgementReader : RejectedAcknowledgementReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): RejectedAcknowledgementData {
        val data = objectOf(dataJson)
        val reasons = data.get("reasons")
        if (reasons == null || !reasons.isArray) {
            throw ContractViolation("AcknowledgementReceived member reasons is missing or not an array")
        }
        return RejectedAcknowledgementData(
            docUuid = text(data, "docUuid", "AcknowledgementReceived"),
            version = versionOf(data),
            reasons = (0 until reasons.size()).map { reasonOf(reasons.get(it)) },
        )
    }

    private fun objectOf(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AcknowledgementReceived data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("AcknowledgementReceived data is not a JSON object")
        }
        return data
    }

    private fun reasonOf(node: JsonNode): RejectionReason {
        if (!node.isObject) throw ContractViolation("AcknowledgementReceived reason is not an object")
        return RejectionReason(
            code = text(node, "code", "AcknowledgementReceived reason"),
            message = text(node, "message", "AcknowledgementReceived reason"),
        )
    }

    private fun text(
        node: JsonNode,
        name: String,
        owner: String,
    ): String {
        val member = node.get(name)
        if (member == null || !member.isString || member.asString().isBlank()) {
            throw ContractViolation("$owner member $name is missing or not a non-blank string")
        }
        return member.asString()
    }

    private fun versionOf(data: JsonNode): Int {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) {
            throw ContractViolation("AcknowledgementReceived member version is missing or not an integer of at least 1")
        }
        return node.asInt()
    }
}
