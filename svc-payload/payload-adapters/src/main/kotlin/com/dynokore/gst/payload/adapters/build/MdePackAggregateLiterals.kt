package com.dynokore.gst.payload.adapters.build

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The placeholder literal of one aggregate type: the business term it fills and the literal text. */
data class AggregateLiteral(
    val businessTermId: String,
    val literal: String,
)

/** An `aggregateDefaults` entry is malformed or two entries name the same aggregate type. */
class AggregateLiteralParseException(
    message: String,
) : RuntimeException(message)

/**
 * The aggregate placeholder literals of one MDE pack version, read from the pack's `artefact.contents` JSON. Created
 * per build; no cache and no static state. Fail closed: a malformed or duplicate entry throws, never the first entry.
 */
class MdePackAggregateLiterals(
    private val contentsJson: String,
) {
    fun literalFor(aggregateType: String): AggregateLiteral? {
        val defaults = JsonMapper().readTree(contentsJson).path("aggregateDefaults")
        if (defaults.isMissingNode || defaults.isNull) return null
        if (!defaults.isArray) throw AggregateLiteralParseException("aggregateDefaults must be an array")
        val entries = defaults.map(::entry)
        val duplicated = entries.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw AggregateLiteralParseException("aggregateDefaults has two entries for $duplicated")
        }
        return entries.firstOrNull { it.first == aggregateType }?.second
    }

    private fun entry(node: JsonNode): Pair<String, AggregateLiteral> =
        Pair(
            text(node, "aggregateType"),
            AggregateLiteral(text(node, "businessTermId"), text(node, "literal")),
        )

    private fun text(
        node: JsonNode,
        member: String,
    ): String {
        val value = node.path(member)
        if (!value.isString || value.stringValue().isBlank()) {
            throw AggregateLiteralParseException("aggregateDefaults.$member must be a non-blank string")
        }
        return value.stringValue()
    }
}
