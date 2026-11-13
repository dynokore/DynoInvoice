package com.dynokore.gst.consoleBff.adapters.duedates

import com.dynokore.gst.consoleBff.application.duedates.DueDateChange
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateChangedReader
import com.dynokore.gst.consoleBff.application.duedates.PeriodKey
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Shared strict parsing of the two due-date payload readers; every invalid input is a `ContractViolation`. */
internal object DueDatePayload {
    private val mapper = ObjectMapper()

    fun parse(
        dataJson: String,
        type: String,
    ): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("$type data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("$type data is not a JSON object")
        return data
    }

    fun text(
        node: JsonNode,
        name: String,
        type: String,
    ): String {
        val member = node.get(name)
        if (member == null || !member.isString || member.asString().isBlank()) {
            throw ContractViolation("$type member $name is missing or not a non-blank string")
        }
        return member.asString()
    }

    fun date(
        node: JsonNode,
        name: String,
        type: String,
    ): LocalDate {
        val member = node.get(name)
        if (member == null || !member.isString) throw ContractViolation("$type member $name is missing or not a date")
        return try {
            LocalDate.parse(member.asString())
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$type member $name is not a calendar date", e)
        }
    }
}

/** [PeriodDueDateChangedReader] over Jackson (period-due-date-changed-payload.schema.json). */
class JacksonPeriodDueDateChangedReader : PeriodDueDateChangedReader {
    override fun read(dataJson: String): DueDateChange {
        val data = DueDatePayload.parse(dataJson, TYPE)
        val version = data.get("dueDateVersion")
        val valid = version != null && version.isIntegralNumber && version.canConvertToLong()
        if (!valid || version.asLong() < 1) {
            throw ContractViolation("$TYPE dueDateVersion is missing or not an integer of at least 1")
        }
        return DueDateChange(
            key = PeriodKey(DueDatePayload.text(data, "entityRef", TYPE), DueDatePayload.text(data, "period", TYPE)),
            newDueDate = DueDatePayload.date(data, "newFilingDueDate", TYPE),
            version = version.asLong(),
        )
    }

    private companion object {
        const val TYPE = "PeriodDueDateChanged"
    }
}
