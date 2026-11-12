package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.period.PeriodRecord
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedData
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [PeriodRecordsChangedDataReader] over Jackson. Reads `entityRef`, `version` (an integer of at least 1) and the
 * `periods` with `periodRef`, `periodStart` and `periodEnd` by exact member name; unknown members are ignored. A
 * missing or malformed member is a [ContractViolation] whose message names the member, never its value.
 */
class JacksonPeriodRecordsChangedDataReader : PeriodRecordsChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): PeriodRecordsChangedData {
        val data = parse(dataJson)
        val periods = data.get("periods")
        if (periods == null || !periods.isArray) {
            throw ContractViolation("PeriodRecordsChanged data member periods is missing or not an array")
        }
        return PeriodRecordsChangedData(
            entityRef = data.text("entityRef"),
            version = versionOf(data),
            periods = periods.toList().map { period(it) },
        )
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("PeriodRecordsChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("PeriodRecordsChanged data is not a JSON object")
        }
        return data
    }

    private fun period(node: JsonNode): PeriodRecord {
        if (!node.isObject) throw ContractViolation("PeriodRecordsChanged period is not a JSON object")
        return PeriodRecord(
            periodRef = node.text("periodRef"),
            periodStart = dateOf(node, "periodStart"),
            periodEnd = dateOf(node, "periodEnd"),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("PeriodRecordsChanged data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun dateOf(
        node: JsonNode,
        name: String,
    ): LocalDate =
        try {
            LocalDate.parse(node.text(name))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("PeriodRecordsChanged $name is not a date", e)
        }

    private fun versionOf(data: JsonNode): Int {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) {
            throw ContractViolation("PeriodRecordsChanged version is missing or not an integer of at least 1")
        }
        return node.asInt()
    }
}
