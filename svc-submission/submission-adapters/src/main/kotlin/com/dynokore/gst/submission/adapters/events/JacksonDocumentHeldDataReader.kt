package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.register.DocumentHeldData
import com.dynokore.gst.submission.application.register.DocumentHeldDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * [DocumentHeldDataReader] over Jackson (document-held-payload.schema.json). Reads `docUuid`, `docsequence` (an
 * integer of at least 1), `holdReasons` (a non-empty array of non-empty strings), `entityRef`, `direction`,
 * `documentDate`, the optional `postingTimestamp` and the optional `invoiceIssuedOrReceived`; `prescribedPeriodRef`
 * is required by the contract but never used, and unknown members are ignored. `postingDate` is the date part of
 * `postingTimestamp` as written: no zone conversion (the source system's own date; the offset is optional). A missing
 * or malformed member is a [ContractViolation] that names the member, never its value.
 */
class JacksonDocumentHeldDataReader : DocumentHeldDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): DocumentHeldData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("DocumentHeld data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("DocumentHeld data is not a JSON object")
        data.text("prescribedPeriodRef")
        return DocumentHeldData(
            docUuid = data.text("docUuid"),
            docsequence = sequenceOf(data),
            holdReasons = reasonsOf(data),
            entityRef = data.text("entityRef"),
            direction = data.text("direction"),
            documentDate = dateOf(data.text("documentDate"), "documentDate"),
            postingDate = if (data.has("postingTimestamp")) postingDateOf(data.text("postingTimestamp")) else null,
            invoiceIssuedOrReceived = flagOf(data),
        )
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("DocumentHeld data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun sequenceOf(data: JsonNode): Long {
        val node = data.get("docsequence")
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong()
        if (!valid || node.asLong() < 1) {
            throw ContractViolation("DocumentHeld docsequence is missing or not an integer of at least 1")
        }
        return node.asLong()
    }

    private fun reasonsOf(data: JsonNode): List<String> {
        val node = data.get("holdReasons")
        val valid = node != null && node.isArray && node.size() > 0 && node.values().all { it.isString }
        if (!valid) throw ContractViolation("DocumentHeld holdReasons is missing, empty or not an array of strings")
        return node.values().map { it.asString() }
    }

    private fun flagOf(data: JsonNode): Boolean? {
        val node = data.get("invoiceIssuedOrReceived") ?: return null
        if (!node.isBoolean) throw ContractViolation("DocumentHeld invoiceIssuedOrReceived is not a boolean")
        return node.asBoolean()
    }

    private fun dateOf(
        text: String,
        name: String,
    ): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("DocumentHeld $name is not a date", e)
        }

    /** The date part as written: an offset date-time or a local date-time (offset optional); never converted. */
    private fun postingDateOf(text: String): LocalDate =
        try {
            OffsetDateTime.parse(text).toLocalDate()
        } catch (ignored: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toLocalDate()
            } catch (e: DateTimeParseException) {
                throw ContractViolation("DocumentHeld postingTimestamp is not a date-time", e)
            }
        }
}
