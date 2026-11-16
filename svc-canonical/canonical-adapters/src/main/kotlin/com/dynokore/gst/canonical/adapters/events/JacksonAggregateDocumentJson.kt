package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.aggregate.AGGREGATE_ITEM_NAME
import com.dynokore.gst.canonical.application.aggregate.AGGREGATE_UNIT
import com.dynokore.gst.canonical.application.aggregate.AggregateAmounts
import com.dynokore.gst.canonical.application.aggregate.AggregateDocumentJson
import com.dynokore.gst.canonical.application.aggregate.MergedLine
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * [AggregateDocumentJson] over Jackson: the `canonicalDocument` of an aggregate (INV-REQ-437). Every amount comes from
 * the arguments; nothing is computed here. The supplier is the object with the single member `gstn`, the buyer is
 * empty, and the identity triple is the one of the [SupplierIdentity] (no service is called).
 */
class JacksonAggregateDocumentJson : AggregateDocumentJson {
    private val mapper = JsonMapper.builder().build()

    override fun build(
        lines: List<MergedLine>,
        amounts: AggregateAmounts,
        isCreditNote: Boolean,
        aggregateType: String,
        supplier: SupplierIdentity,
    ): String {
        val root = mapper.createObjectNode()
        wrap(root, "documentTypeCode", NONE).put(VALUE, if (isCreditNote) CREDIT_NOTE else INVOICE)
        wrap(root, "currency", NONE).put(VALUE, CURRENCY)
        wrap(root, "taxCurrencyCode", NONE).put(VALUE, CURRENCY)
        wrap(root, "aggregateType", NONE).put(VALUE, aggregateType)
        val parties = wrap(root, "parties", IDENTIFYING).putObject(VALUE)
        parties.putObject("supplier").put("gstn", supplier.supplierGstn)
        parties.putObject("buyer")
        wrap(root, "identityTriple", IDENTIFYING).putObject(VALUE).apply {
            put("senderId", supplier.senderId)
            put("supplierId", supplier.supplierId)
            put("supplierGstn", supplier.supplierGstn)
        }
        val taxByCode = amounts.subtotals.associate { it.categoryCode to it.taxAmount }
        val lineArray = wrap(root, "lines", NONE).putArray(VALUE)
        lines.forEachIndexed { index, line ->
            lineArray.addObject().apply {
                put("lineNumber", index + 1)
                put("description", AGGREGATE_ITEM_NAME)
                put("unitCode", AGGREGATE_UNIT)
                put("taxCode", line.categoryCode)
                put("lineAmount", text(line.amount))
                put("taxAmount", text(checkNotNull(taxByCode[line.categoryCode])))
            }
        }
        val subtotals = wrap(root, "taxSubtotals", NONE).putArray(VALUE)
        amounts.subtotals.forEach {
            subtotals.addObject().apply {
                put("taxCode", it.categoryCode)
                put("taxableAmount", text(it.taxableAmount))
                put("taxAmount", text(it.taxAmount))
            }
        }
        wrap(root, "totals", NONE).putObject(VALUE).apply {
            put("totalExcludingTax", text(amounts.totalExcludingTax))
            put("totalTax", text(amounts.totalTax))
            put("totalIncludingTax", text(amounts.totalIncludingTax))
        }
        return mapper.writeValueAsString(root)
    }

    private fun wrap(
        root: ObjectNode,
        member: String,
        classification: String,
    ): ObjectNode = root.putObject(member).also { it.put(CLASSIFICATION, classification) }

    private fun text(amount: BigDecimal) = amount.toPlainString()

    private companion object {
        const val VALUE = "value"
        const val CLASSIFICATION = "classification"
        const val NONE = "NONE"
        const val IDENTIFYING = "IDENTIFYING"
        const val CURRENCY = "SGD"
        const val INVOICE = "380"
        const val CREDIT_NOTE = "381"
    }
}
