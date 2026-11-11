package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.pack.SpecAcceptance
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Decodes `acceptances` of an ENDPOINT_ACCEPTANCE pack (`endpoint-acceptance-pack-contents.schema.json`,
 * INV-REQ-589). A member the schema does not name is ignored (API-41). Contents are never repaired and no default is
 * applied: a malformed date, a missing `accepted_from`, an `accepted_to` before `accepted_from` or a repeated pair
 * (`specVersion`, `accepted_from`) fails naming the member path.
 */
object EndpointAcceptancePackContents {
    private const val PACK_TYPE = "ENDPOINT_ACCEPTANCE"
    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    fun decode(contents: ByteArray): List<SpecAcceptance> {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val rows = root.array("acceptances", min = 1, max = 200).map(::row)
        rows.groupBy { it.specVersion to it.acceptedFrom }.entries.firstOrNull { it.value.size > 1 }?.let {
            root.fail("acceptances", "holds specVersion '${it.key.first}' from ${it.key.second} twice")
        }
        return rows
    }

    private fun row(node: ContentsNode): SpecAcceptance {
        val version = node.text("specVersion", min = 1, max = 32)
        val from = date(node, "accepted_from", node.text("accepted_from", pattern = DATE))
        val to = node.optionalText("accepted_to", pattern = DATE)?.let { date(node, "accepted_to", it) }
        if (to != null && to.isBefore(from)) node.fail("accepted_to", "is before accepted_from: '$to' < '$from'")
        return SpecAcceptance(version, from, to)
    }

    private fun date(
        node: ContentsNode,
        name: String,
        text: String,
    ): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            node.fail(name, "is not a date: '$text'")
        }
}
