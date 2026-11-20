package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.register.NetDerivedCreditNote
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The members of a `NetDerivationRefused` event the consumer reads (net-derivation-refused-payload.schema.json). */
data class NetDerivationRefusedData(
    val originalDocUuid: String,
    val requestEventId: String,
    val reason: String,
    val creditNoteDocUuids: List<String>,
)

/**
 * Reads `NetDerivationRefused` over Jackson and hands `reason` and the credit note list to the
 * [CreditNoteCorrectionHandler] (INV-REQ-558 behavior 6). `originalDocUuid` (canonical UUID), `requestEventId` and
 * `reason` (non-empty strings; the enum is extensible, so an unknown reason is kept) are required; `creditNotes` is
 * optional, each item needs a canonical `docUuid` and a `version` of at least 1, and at most 50 distinct items. A
 * missing or malformed member is a [ContractViolation] (dead letter at once) that names the member, never its value.
 * An event that names no credit note changes nothing: IGNORED. It runs in the caller's transaction and tenant.
 */
class NetDerivationRefusedReader(
    private val handler: CreditNoteCorrectionHandler,
) : EventHandler {
    private val mapper = ObjectMapper()

    override fun handle(event: Envelope): HandleOutcome {
        val data = read(event.dataJson)
        if (data.creditNoteDocUuids.isEmpty()) return HandleOutcome.IGNORED
        handler.onRefused(data.creditNoteDocUuids, data.reason)
        return HandleOutcome.APPLIED
    }

    /** @throws ContractViolation when a member is missing or malformed. */
    fun read(dataJson: String): NetDerivationRefusedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("NetDerivationRefused data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("NetDerivationRefused data is not a JSON object")
        return NetDerivationRefusedData(
            originalDocUuid = uuid(data, "originalDocUuid"),
            requestEventId = text(data, "requestEventId"),
            reason = text(data, "reason"),
            creditNoteDocUuids = creditNotes(data),
        )
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("NetDerivationRefused data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun uuid(
        data: JsonNode,
        name: String,
    ): String {
        val value = text(data, name)
        if (!runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            throw ContractViolation("NetDerivationRefused data member $name is not a canonical UUID")
        }
        return value
    }

    private fun creditNotes(data: JsonNode): List<String> {
        val node = data.get("creditNotes") ?: return emptyList()
        return NetCreditNotes.parse(node, "NetDerivationRefused creditNotes").map { it.docUuid }
    }
}

/**
 * The shared reading of a `creditNotes` array of the net derivation contracts: 1 to 50 distinct items, each with a
 * canonical `docUuid` and a `version` of at least 1. Any other shape is a [ContractViolation] naming [member].
 */
internal object NetCreditNotes {
    private const val MAX = 50

    fun parse(
        node: JsonNode,
        member: String,
    ): List<NetDerivedCreditNote> {
        if (!node.isArray || node.size() !in 1..MAX) violation("$member is not an array of 1 to $MAX items")
        val notes = node.values().map { item -> item(item, member) }
        if (notes.toSet().size != notes.size) violation("$member has a duplicate")
        return notes
    }

    private fun item(
        item: JsonNode,
        member: String,
    ): NetDerivedCreditNote {
        val docUuid = item.get("docUuid")
        val text = if (docUuid != null && docUuid.isString) docUuid.asString() else null
        if (!item.isObject || text == null || !canonical(text)) violation("$member item has no canonical docUuid")
        return NetDerivedCreditNote(text, versionOf(item.get("version"), member))
    }

    fun versionOf(
        node: JsonNode?,
        member: String,
    ): Int {
        val valid = node != null && node.isIntegralNumber && node.canConvertToInt()
        if (!valid || node.asInt() < 1) violation("$member version is not an integer of at least 1")
        return node.asInt()
    }

    fun canonical(text: String): Boolean = runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)

    fun violation(message: String): Nothing = throw ContractViolation(message)
}
