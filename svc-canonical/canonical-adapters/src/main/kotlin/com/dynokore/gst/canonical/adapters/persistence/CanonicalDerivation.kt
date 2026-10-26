package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.domain.document.CanonicalShapeException
import com.dynokore.gst.canonical.domain.document.DerivedLine
import com.dynokore.gst.canonical.domain.document.DerivedParty
import com.dynokore.gst.canonical.domain.document.DerivedRows
import com.dynokore.gst.canonical.domain.document.DerivedSubtotal
import com.dynokore.gst.canonical.domain.document.PartyRole
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * Derives the line, subtotal and party rows from a stored canonical document (INV-REQ-2572; INV-NOTE-3 sections 10.2
 * and 10.6) by the shape of canonical/schemas/canonical-version-created-payload.schema.json at e5180d6, property
 * `canonicalDocument`. A document that differs from that shape (INV-Q-11077 A) throws [CanonicalShapeException].
 * Money is the decimal string of the contract (API-12), never a JSON number.
 */
object CanonicalDerivation {
    // floating numbers are read as exact decimals (INV-Q-11079 A): a double would round a 23-digit quantity
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val money = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")
    private val price = Regex("^-?\\d{1,17}(\\.\\d{1,6})?$")
    private const val QUANTITY_INTEGER_DIGITS = 17
    private const val QUANTITY_SCALE = 6

    fun derive(canonicalJson: String): DerivedRows {
        val document = mapper.readTree(canonicalJson)
        shape(document.isObject) { "the canonical document is not a JSON object" }
        val lines = items(document, "lines").map(::line)
        val subtotals = items(document, "taxSubtotals").map(::subtotal)
        shape(lines.map { it.lineNumber }.toSet().size == lines.size) { "lines.value repeats a lineNumber" }
        shape(subtotals.map { it.taxCode }.toSet().size == subtotals.size) { "taxSubtotals.value repeats a taxCode" }
        return DerivedRows(lines, subtotals, parties(document))
    }

    private fun wrapped(
        document: JsonNode,
        key: String,
    ): JsonNode {
        val field = document.get(key)
        shape(field != null && field.isObject && field.has("value")) { "$key is not a {value, classification} object" }
        return field.get("value")
    }

    private fun items(
        document: JsonNode,
        key: String,
    ): List<JsonNode> {
        val value = wrapped(document, key)
        shape(value.isArray) { "$key.value is not an array" }
        return value.toList().onEach { shape(it.isObject) { "an item of $key.value is not an object" } }
    }

    private fun line(item: JsonNode): DerivedLine {
        val number = item.get("lineNumber")
        shape(number != null && number.isIntegralNumber && number.canConvertToInt() && number.asInt() >= 1) {
            "lines.value lineNumber is not an integer of at least 1"
        }
        val quantity = item.get("quantity")
        shape(quantity != null && quantity.isNumber) { "lines.value quantity is not a number" }
        val quantityValue = quantity.decimalValue()
        shape(
            quantityValue.scale() <= QUANTITY_SCALE &&
                quantityValue.precision() - quantityValue.scale() <= QUANTITY_INTEGER_DIGITS,
        ) { "lines.value quantity does not fit numeric(23,6)" }
        return DerivedLine(
            number.asInt(),
            text(item, "description", "lines.value"),
            quantityValue,
            decimal(item, "unitPrice", price, "lines.value"),
            decimal(item, "lineAmount", money, "lines.value"),
            text(item, "taxCode", "lines.value"),
            decimal(item, "taxAmount", money, "lines.value"),
        )
    }

    private fun subtotal(item: JsonNode) =
        DerivedSubtotal(
            text(item, "taxCode", "taxSubtotals.value"),
            decimal(item, "taxableAmount", money, "taxSubtotals.value"),
            decimal(item, "taxAmount", money, "taxSubtotals.value"),
        )

    private fun parties(document: JsonNode): List<DerivedParty> {
        val value = wrapped(document, "parties")
        shape(value.isObject) { "parties.value is not an object" }
        return PartyRole.entries.map { role ->
            val party = value.get(role.column)
            shape(party != null && party.isObject) { "parties.value.${role.column} is not an object" }
            DerivedParty(role, party.toString())
        }
    }

    private fun text(
        item: JsonNode,
        field: String,
        where: String,
    ): String {
        val node = item.get(field)
        shape(node != null && node.isString) { "$where $field is not a string" }
        return node.asString()
    }

    private fun decimal(
        item: JsonNode,
        field: String,
        pattern: Regex,
        where: String,
    ): BigDecimal {
        val text = text(item, field, where)
        shape(pattern.matches(text)) { "$where $field is not a decimal string of the contract pattern" }
        return BigDecimal(text)
    }

    private inline fun shape(
        holds: Boolean,
        message: () -> String,
    ) {
        if (!holds) throw CanonicalShapeException(message())
    }
}
