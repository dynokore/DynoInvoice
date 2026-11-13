package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.pack.PackActivatedData
import com.dynokore.gst.submission.application.pack.PackActivatedDataReader
import com.dynokore.gst.submission.application.pack.PackStagedData
import com.dynokore.gst.submission.application.pack.PackStagedDataReader
import com.dynokore.gst.submission.application.pack.RawMargin
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val mapper = ObjectMapper()

private fun parse(
    event: String,
    dataJson: String,
): JsonNode {
    val data =
        try {
            mapper.readTree(dataJson)
        } catch (e: JacksonException) {
            throw ContractViolation("$event data is not JSON", e)
        }
    if (data == null || !data.isObject) throw ContractViolation("$event data is not a JSON object")
    return data
}

private fun JsonNode.text(
    event: String,
    name: String,
): String {
    val node = get(name)
    if (node == null || !node.isString || node.asString().isEmpty()) {
        throw ContractViolation("$event data member $name is missing or not a non-empty string")
    }
    return node.asString()
}

/**
 * [PackStagedDataReader] over Jackson. Reads `packIdentifier`, `packType` and `version` by exact member name and the
 * margin from `artefact.contents.safetyMargin` only: the value when the node is an integral number, the unit when it
 * is a string; validation is the handler's. Unknown members are ignored. A missing required member is a
 * [ContractViolation] whose message names the member, never its value.
 */
class JacksonPackStagedDataReader : PackStagedDataReader {
    override fun read(dataJson: String): PackStagedData {
        val data = parse(EVENT, dataJson)
        val margin = data.get("artefact")?.get("contents")?.get("safetyMargin")
        return PackStagedData(
            packIdentifier = data.text(EVENT, "packIdentifier"),
            packType = data.text(EVENT, "packType"),
            version = data.text(EVENT, "version"),
            margin = margin?.takeIf { it.isObject }?.let { rawMargin(it) },
        )
    }

    private fun rawMargin(node: JsonNode): RawMargin {
        val value = node.get("value")
        val unit = node.get("unit")
        return RawMargin(
            value = value?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong(),
            unit = unit?.takeIf { it.isString }?.asString(),
        )
    }

    private companion object {
        const val EVENT = "RulePackStaged"
    }
}

/** [PackActivatedDataReader] over Jackson: `packIdentifier`, `version` and an ISO date `effectiveFrom`. */
class JacksonPackActivatedDataReader : PackActivatedDataReader {
    override fun read(dataJson: String): PackActivatedData {
        val data = parse(EVENT, dataJson)
        return PackActivatedData(
            packIdentifier = data.text(EVENT, "packIdentifier"),
            version = data.text(EVENT, "version"),
            effectiveFrom =
                try {
                    LocalDate.parse(data.text(EVENT, "effectiveFrom"))
                } catch (e: DateTimeParseException) {
                    throw ContractViolation("$EVENT effectiveFrom is not a date", e)
                },
        )
    }

    private companion object {
        const val EVENT = "RulePackActivated"
    }
}
