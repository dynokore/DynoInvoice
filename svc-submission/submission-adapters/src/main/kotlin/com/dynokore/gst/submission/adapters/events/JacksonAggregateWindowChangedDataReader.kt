package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedData
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.WindowChangeState
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [AggregateWindowChangedDataReader] over Jackson. Reads `entityRef`, `prescribedPeriod`, `window.from`, `window.to`,
 * `state` by exact member name and the optional `stateVersion` (an integer of at least 1; 0 when absent). A missing
 * or malformed member is a [ContractViolation] whose message names the member, never its value.
 */
class JacksonAggregateWindowChangedDataReader : AggregateWindowChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): AggregateWindowChangedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AggregateWindowChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("AggregateWindowChanged data is not a JSON object")
        }
        val window = windowOf(data)
        return AggregateWindowChangedData(
            entityRef = data.text("entityRef", "entityRef"),
            prescribedPeriod = data.text("prescribedPeriod", "prescribedPeriod"),
            from = dateOf(window.text("from", "window.from")),
            to = dateOf(window.text("to", "window.to")),
            state = stateOf(data.text("state", "state")),
            stateVersion = versionOf(data),
        )
    }

    private fun windowOf(data: JsonNode): JsonNode {
        val window = data.get("window")
        if (window == null || !window.isObject) {
            throw ContractViolation("AggregateWindowChanged data member window is missing or not an object")
        }
        return window
    }

    private fun JsonNode.text(
        name: String,
        label: String,
    ): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("AggregateWindowChanged data member $label is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("AggregateWindowChanged window bound is not a date", e)
        }

    private fun stateOf(text: String): WindowChangeState =
        WindowChangeState.entries.firstOrNull { it.name == text }
            ?: throw ContractViolation("AggregateWindowChanged state is not a known state")

    private fun versionOf(data: JsonNode): Long {
        val node = data.get("stateVersion") ?: return 0L
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
            throw ContractViolation("AggregateWindowChanged stateVersion is not an integer of at least 1")
        }
        return node.asLong()
    }
}
