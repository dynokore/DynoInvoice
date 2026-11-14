package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.startdate.EntityProfileChangedData
import com.dynokore.gst.submission.application.startdate.EntityProfileChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [EntityProfileChangedDataReader] over Jackson. Reads `entityRef`, `effectiveDate` and, from the item of
 * `changedAttributes` whose `attribute` equals exactly `obligationStartDate` (the first if several), its `newValue`
 * as an ISO local date. A missing or malformed member is a [ContractViolation] whose message names the member,
 * never its value.
 */
class JacksonEntityProfileChangedDataReader : EntityProfileChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): EntityProfileChangedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("EntityProfileChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("EntityProfileChanged data is not a JSON object")
        }
        return EntityProfileChangedData(
            entityRef = data.text("entityRef"),
            obligationStartDate = startDateOf(data),
            effectiveDate = dateOf(data.text("effectiveDate"), "effectiveDate"),
        )
    }

    private fun startDateOf(data: JsonNode): LocalDate? {
        val changed = data.get("changedAttributes")
        val item = if (changed != null && changed.isArray) changed.firstOrNull { isStartDate(it) } else null
        return item?.let { dateOf(it.text("newValue"), "newValue") }
    }

    private fun isStartDate(item: JsonNode): Boolean {
        val attribute = item.get("attribute")
        return item.isObject && attribute != null && attribute.isString && attribute.asString() == ATTRIBUTE
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("EntityProfileChanged data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun dateOf(
        text: String,
        member: String,
    ): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("EntityProfileChanged $member is not a date", e)
        }

    private companion object {
        const val ATTRIBUTE = "obligationStartDate"
    }
}
