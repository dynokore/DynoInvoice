package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedData
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.application.register.NetDerivationData
import com.dynokore.gst.submission.application.register.NetDerivedCreditNote
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * [CanonicalVersionCreatedDataReader] over Jackson (canonical-version-created-payload.schema.json). A missing or
 * malformed member is a [ContractViolation]. The payload map keeps exactly `canonicalDocument` and `routingFacts`
 * (each when present), shape unchanged; nothing else of the event is kept.
 */
class JacksonCanonicalVersionCreatedDataReader : CanonicalVersionCreatedDataReader {
    private val mapper = ObjectMapper()

    private companion object {
        val REASONS = setOf("BUYER_REJECTED", "ISSUANCE_REFUSED", "NETTED_INTO_REVISION")
    }

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
        val scopeVerdict = data.text("scopeVerdict")
        return CanonicalVersionCreatedData(
            docUuid = data.text("docUuid"),
            version = versionOf(data),
            transactionDate = dateOf(data.text("transactionDate")),
            prescribedPeriodRef = data.text("prescribedPeriodRef"),
            scopeVerdict = scopeVerdict,
            payload = payloadOf(data),
            notSubmittableReason = reasonOf(data, scopeVerdict),
            precedingDocUuid = precedingOf(data),
            netDerivation = netDerivationOf(data),
        )
    }

    /** Present only as one of the schema enum values and only with `NOT_SUBMITTABLE` (fail closed). */
    private fun reasonOf(
        data: JsonNode,
        scopeVerdict: String,
    ): String? {
        val node = data.get("notSubmittableReason") ?: return null
        val member = "CanonicalVersionCreated data member notSubmittableReason"
        if (!node.isString || node.asString() !in REASONS) {
            throw ContractViolation("$member is not an allowed reason")
        }
        if (scopeVerdict != "NOT_SUBMITTABLE") {
            throw ContractViolation("$member needs a NOT_SUBMITTABLE verdict")
        }
        return node.asString()
    }

    /** Present only as a lower-case canonical UUID, the form of `docUuid` in the register. */
    private fun precedingOf(data: JsonNode): String? {
        val node = data.get("precedingDocUuid") ?: return null
        val text = if (node.isString) node.asString() else null
        val canonical = text != null && canonicalUuid(text)
        if (!canonical) {
            throw ContractViolation("CanonicalVersionCreated data member precedingDocUuid is not a canonical UUID")
        }
        return text
    }

    /**
     * The optional `netDerivation` (canonical-version-created-payload.schema.json): `kind` (non-empty string, the enum
     * is extensible), `baseVersion` (integer of at least 1) and `creditNotes` (see [NetCreditNotes]). Absent reads as
     * null; any other shape is a [ContractViolation].
     */
    private fun netDerivationOf(data: JsonNode): NetDerivationData? {
        val node = data.get("netDerivation") ?: return null
        val member = "CanonicalVersionCreated data member netDerivation"
        if (!node.isObject) NetCreditNotes.violation("$member is not an object")
        val kind = node.get("kind")
        if (kind == null || !kind.isString || kind.asString().isEmpty()) {
            NetCreditNotes.violation("$member kind is missing or not a non-empty string")
        }
        val base = NetCreditNotes.versionOf(node.get("baseVersion"), "$member base")
        val notes = node.get("creditNotes") ?: NetCreditNotes.violation("$member creditNotes is missing")
        return NetDerivationData(base, NetCreditNotes.parse(notes, "$member creditNotes"))
    }

    private fun canonicalUuid(text: String): Boolean =
        runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)

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
