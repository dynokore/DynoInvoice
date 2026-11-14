package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.completeness.FeedHealthChangedData
import com.dynokore.gst.submission.application.completeness.FeedHealthChangedDataReader
import com.dynokore.gst.submission.application.completeness.FeedHealthState
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [FeedHealthChangedDataReader] over Jackson. Reads `feed`, `entityRef`, `period`, `control`, `state` by exact member
 * name and the optional `stateVersion` (an integer of at least 1; 0 when absent). A missing or malformed member is a
 * [ContractViolation] whose message names the member, never its value.
 */
class JacksonFeedHealthChangedDataReader : FeedHealthChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): FeedHealthChangedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("FeedHealthChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("FeedHealthChanged data is not a JSON object")
        }
        return FeedHealthChangedData(
            feed = data.text("feed"),
            entityRef = data.text("entityRef"),
            period = data.text("period"),
            control = data.text("control"),
            state = stateOf(data.text("state")),
            stateVersion = versionOf(data),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("FeedHealthChanged data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun stateOf(text: String): FeedHealthState =
        FeedHealthState.entries.firstOrNull { it.name == text }
            ?: throw ContractViolation("FeedHealthChanged state is not a known state")

    private fun versionOf(data: JsonNode): Long {
        val node = data.get("stateVersion") ?: return 0L
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
            throw ContractViolation("FeedHealthChanged stateVersion is not an integer of at least 1")
        }
        return node.asLong()
    }
}
