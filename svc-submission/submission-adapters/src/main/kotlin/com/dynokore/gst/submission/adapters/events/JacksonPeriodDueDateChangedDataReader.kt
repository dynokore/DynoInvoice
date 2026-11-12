package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.register.PeriodDueDateChangedData
import com.dynokore.gst.submission.application.register.PeriodDueDateChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [PeriodDueDateChangedDataReader] over Jackson. Reads `entityRef`, `period`, `newFilingDueDate` and `dueDateVersion`
 * (an integer of at least 1) by exact member name; the envelope time is never read. A missing or malformed member is
 * a [ContractViolation] whose message names the member, never its value.
 */
class JacksonPeriodDueDateChangedDataReader : PeriodDueDateChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): PeriodDueDateChangedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("PeriodDueDateChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("PeriodDueDateChanged data is not a JSON object")
        }
        return PeriodDueDateChangedData(
            entityRef = data.text("entityRef"),
            period = data.text("period"),
            newFilingDueDate = dateOf(data.text("newFilingDueDate")),
            dueDateVersion = versionOf(data),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("PeriodDueDateChanged data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("PeriodDueDateChanged newFilingDueDate is not a date", e)
        }

    private fun versionOf(data: JsonNode): Long {
        val node = data.get("dueDateVersion")
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong()
        if (!valid || node.asLong() < 1) {
            throw ContractViolation("PeriodDueDateChanged dueDateVersion is missing or not an integer of at least 1")
        }
        return node.asLong()
    }
}
