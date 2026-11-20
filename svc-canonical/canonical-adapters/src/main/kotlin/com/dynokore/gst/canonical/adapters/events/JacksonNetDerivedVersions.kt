package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.netting.NetDerivedVersions
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.NetLine
import com.dynokore.gst.canonical.domain.netting.NetTotals
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Builds the derived version of a net derivation (INV-REQ-558 behavior 6): the canonical document text of the base
 * version with exactly `lines`, `taxSubtotals` and `totals` replaced by the derived values, every other member as it
 * was in the base, in its place and with its own classification. A derived line that has the line number of a base
 * line keeps that base line unchanged; the adjustment lines are new. Money is a decimal string of scale 2 (API-12).
 * [baseOf] returns the stored base version (its canonical text and metadata) and fails for an unknown one.
 */
class JacksonNetDerivedVersions(
    private val baseOf: (docUuid: UUID, version: Int) -> NewVersion,
) : NetDerivedVersions {
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    override fun build(
        docUuid: UUID,
        baseVersion: Int,
        derived: Derived,
    ): NewVersion {
        val base = baseOf(docUuid, baseVersion)
        val root = mapper.readTree(base.canonical) as ObjectNode
        val baseLines = member(root, LINES).get(VALUE).associateBy { it.get("lineNumber").asInt() }
        val lines = mapper.createArrayNode()
        derived.lines.forEach { line -> lines.add(baseLines[line.lineNumber] ?: adjustment(line)) }
        member(root, LINES).set(VALUE, lines)
        val subtotals = mapper.createArrayNode()
        derived.subtotals.forEach {
            subtotals.add(
                mapper
                    .createObjectNode()
                    .put("taxCode", it.taxCode)
                    .put("taxableAmount", money(it.taxableAmount))
                    .put("taxAmount", money(it.taxAmount)),
            )
        }
        member(root, SUBTOTALS).set(VALUE, subtotals)
        totals(member(root, TOTALS).get(VALUE) as ObjectNode, derived.totals)
        return base.copy(canonical = mapper.writeValueAsString(root))
    }

    private fun member(
        root: ObjectNode,
        name: String,
    ): ObjectNode = checkNotNull(root.get(name) as? ObjectNode) { "the base canonical document has no $name member" }

    private fun adjustment(line: NetLine): JsonNode =
        mapper
            .createObjectNode()
            .put("lineNumber", line.lineNumber)
            .put("description", ADJUSTMENT_DESCRIPTION)
            .also { it.set(QUANTITY, mapper.getNodeFactory().numberNode(line.quantity)) }
            .put("unitPrice", money(line.unitPrice))
            .put("lineAmount", money(line.lineAmount))
            .put("taxCode", line.taxCode)
            .put("taxAmount", money(line.taxAmount))

    private fun totals(
        target: ObjectNode,
        totals: NetTotals,
    ) {
        target.put("totalExcludingTax", money(totals.excludingTax))
        target.put("totalTax", money(totals.tax))
        target.put("totalIncludingTax", money(totals.includingTax))
        totals.sgdExcludingTax?.let { target.put("sgdTotalExcludingTax", money(it)) }
        totals.sgdTax?.let { target.put("sgdTotalTax", money(it)) }
        totals.sgdIncludingTax?.let { target.put("sgdTotalIncludingTax", money(it)) }
    }

    private fun money(amount: BigDecimal): String = amount.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString()

    private companion object {
        const val LINES = "lines"
        const val SUBTOTALS = "taxSubtotals"
        const val TOTALS = "totals"
        const val VALUE = "value"
        const val QUANTITY = "quantity"
        const val SCALE = 2
        const val ADJUSTMENT_DESCRIPTION = "Credit note adjustment"
    }
}

/**
 * Remembers the version [delegate] built last on this thread, so the caller that stages the event of the same
 * version can read its canonical document text without building it twice. Reading before any build fails closed.
 */
class CapturingNetDerivedVersions(
    private val delegate: NetDerivedVersions,
) : NetDerivedVersions {
    private val last = ThreadLocal<NewVersion>()

    override fun build(
        docUuid: UUID,
        baseVersion: Int,
        derived: Derived,
    ): NewVersion = delegate.build(docUuid, baseVersion, derived).also { last.set(it) }

    /** The canonical document text of the version built last on this thread, and forgets it. */
    fun takeCanonical(): String {
        val built = checkNotNull(last.get()) { "no derived version was built on this thread" }
        last.remove()
        return built.canonical
    }
}
