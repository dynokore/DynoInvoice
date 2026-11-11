package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedData
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [CanonicalVersionCreatedDataReader] over Jackson (canonical-version-created-payload.schema.json). A missing or
 * malformed member is a [ContractViolation]. The payload map keeps exactly `canonicalDocument` and `routingFacts`
 * (each when present), shape unchanged; nothing else of the event is kept.
 */
class JacksonCanonicalVersionCreatedDataReader : CanonicalVersionCreatedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): CanonicalVersionCreatedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("CanonicalVersionCreated data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("CanonicalVersionCreated data is not a JSON object")
        }
        return CanonicalVersionCreatedData(
            docUuid = data.text("docUuid"),
            version = versionOf(data),
            transactionDate = dateOf(data.text("transactionDate")),
            prescribedPeriodRef = data.text("prescribedPeriodRef"),
            scopeVerdict = data.text("scopeVerdict"),
            payload = payloadOf(data),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("CanonicalVersionCreated data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun versionOf(data: JsonNode): Int {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) {
            throw ContractViolation("CanonicalVersionCreated version is missing or not an integer of at least 1")
        }
        return node.asInt()
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("CanonicalVersionCreated transactionDate is not a date", e)
        }

    private fun payloadOf(data: JsonNode): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val document = data.get("canonicalDocument")
        if (document != null && document.isObject) {
            out["canonicalDocument"] = plain(document)
        }
        data.get("routingFacts")?.let { out["routingFacts"] = plain(it) }
        return out
    }

    private fun plain(node: JsonNode): Any? =
        when {
            node.isObject -> node.properties().associate { it.key to plain(it.value) }
            node.isArray -> node.values().map { plain(it) }
            node.isString -> node.asString()
            node.isBoolean -> node.asBoolean()
            node.isNull -> null
            else -> node.numberValue()
        }
}
