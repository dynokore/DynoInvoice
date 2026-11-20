package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/** What a `NetDerivationRequested` event asks: the original, the base version it expects, the credit notes to net. */
data class NetDerivationRequest(
    val originalDocUuid: UUID,
    val expectedBaseVersion: Int,
    val creditNotes: List<NetCreditNoteRef>,
)

/**
 * Reads the data of a `NetDerivationRequested` event (INV-REQ-558 behavior 6) and checks it against the rules of
 * `submission/schemas/net-derivation-requested-payload.schema.json` of the pinned bundle: an object; `originalDocUuid`
 * a UUID text; `expectedBaseVersion` an integer of at least 1; `creditNotes` an array of 1 to 50 distinct objects, each
 * with a UUID text `docUuid` and an integer `version` of at least 1; members the schema does not name are ignored
 * (additionalProperties true). The service has no JSON Schema library at run time, so the rules are written here and
 * `NetDerivationRequestedReaderTest` proves they agree with the pinned schema on valid, invalid and look-alike payloads.
 *
 * One rule goes beyond the schema: a credit note `docUuid` that repeats with another version is refused, because the
 * application cannot net one document twice and would fail on every retry. A UUID text is checked in the 8-4-4-4-12
 * hexadecimal form (`format: uuid`), as `UUID.fromString` alone is lenient. Anything that does not validate throws
 * [ContractViolation] (dead letter at once).
 */
class NetDerivationRequestedReader {
    private val json = JsonMapper.builder().build()

    fun read(dataJson: String): NetDerivationRequest {
        val data = parse(dataJson)
        val notes = data.get(CREDIT_NOTES)
        if (notes == null || !notes.isArray || notes.size() !in 1..MAX_NOTES) {
            throw ContractViolation("$CREDIT_NOTES is not an array of 1 to $MAX_NOTES items")
        }
        val items = notes.toList()
        if (items.toSet().size != items.size) throw ContractViolation("$CREDIT_NOTES repeats an item")
        val refs = items.map { note(it) }
        if (refs.map { it.docUuid }.toSet().size != refs.size) throw ContractViolation("$CREDIT_NOTES repeats a docUuid")
        return NetDerivationRequest(uuid(data, ORIGINAL), version(data, BASE_VERSION), refs)
    }

    private fun note(node: JsonNode): NetCreditNoteRef {
        if (!node.isObject) throw ContractViolation("$CREDIT_NOTES holds an item that is not an object")
        return NetCreditNoteRef(uuid(node, DOC_UUID), version(node, VERSION))
    }

    private fun uuid(
        node: JsonNode,
        member: String,
    ): UUID {
        val value = node.get(member)
        if (value == null || !value.isString || !UUID_TEXT.matches(value.asString())) {
            throw ContractViolation("$member is missing or not a UUID text")
        }
        return UUID.fromString(value.asString())
    }

    private fun version(
        node: JsonNode,
        member: String,
    ): Int {
        val value = node.get(member)
        val number = if (value != null && value.isNumber) value.decimalValue() else null
        if (number == null || !isIntegerAtLeastOne(number)) {
            throw ContractViolation("$member is missing or not an integer of at least 1")
        }
        return number.intValueExact()
    }

    private fun isIntegerAtLeastOne(number: BigDecimal): Boolean =
        number.stripTrailingZeros().scale() <= 0 && number >= BigDecimal.ONE && number <= BigDecimal(Int.MAX_VALUE)

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val ORIGINAL = "originalDocUuid"
        const val BASE_VERSION = "expectedBaseVersion"
        const val CREDIT_NOTES = "creditNotes"
        const val DOC_UUID = "docUuid"
        const val VERSION = "version"
        const val MAX_NOTES = 50
        val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
