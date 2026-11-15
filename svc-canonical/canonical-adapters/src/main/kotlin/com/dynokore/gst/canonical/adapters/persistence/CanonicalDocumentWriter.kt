package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.application.document.ContentParty
import com.dynokore.gst.canonical.application.document.ContentTotals
import com.dynokore.gst.canonical.application.document.DocumentContent
import com.dynokore.gst.canonical.application.document.LineTaxFact
import com.dynokore.gst.canonical.application.document.SubtotalResult
import com.dynokore.gst.canonical.application.document.TaxSubtotal
import com.dynokore.gst.canonical.application.document.TaxSubtotals
import com.dynokore.gst.canonical.application.document.TaxedLine
import tools.jackson.core.StreamWriteFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode

/** Lines that lack a member the canonical schema requires and the request leaves optional (fail closed, P5). */
class IncompleteCanonicalDocument(
    val lineNumbers: List<Int>,
) : RuntimeException("canonical document lines lack description, quantity or unitPrice: $lineNumbers")

/**
 * Renders the `canonicalDocument` object of canonical-version-created-payload.schema.json (INV-AC-21599). Declared
 * totals are copied verbatim; absent ones are derived by BR-CO-13, BR-CO-14 and BR-CO-15. Members that the schema does
 * not require are omitted when absent: never null, never an empty string.
 */
class CanonicalDocumentWriter {
    private val mapper = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).build()

    fun write(
        content: DocumentContent,
        documentTypeCode: String,
        lineTax: List<LineTaxFact>,
    ): String {
        val incomplete =
            content.lines
                .filter { it.description == null || it.quantity == null || it.unitPrice == null }
                .map { it.lineNumber }
        if (incomplete.isNotEmpty()) throw IncompleteCanonicalDocument(incomplete)
        require(lineTax.size == content.lines.size) { "lineTax must be index-aligned with the lines" }
        val subtotals = subtotalsOf(content, lineTax)
        val lines = linesOf(content, lineTax)
        val subs =
            subtotals.map {
                mapOf(
                    "taxCode" to it.taxCode,
                    "taxableAmount" to it.taxableAmount,
                    "taxAmount" to it.taxAmount,
                )
            }
        val totals =
            content.declaredTotals?.let { declared(it) } ?: derived(content, subtotals.map { BigDecimal(it.taxAmount) })
        val doc =
            linkedMapOf(
                "documentTypeCode" to wrap(documentTypeCode, "NONE"),
                "currency" to wrap(content.currency, "NONE"),
                "parties" to
                    wrap(mapOf("supplier" to party(content.supplier), "buyer" to party(content.buyer)), "IDENTIFYING"),
                "lines" to wrap(lines, "NONE"),
                "taxSubtotals" to wrap(subs, "NONE"),
                "totals" to wrap(totals, "NONE"),
            )
        return mapper.writeValueAsString(doc)
    }

    private fun subtotalsOf(
        content: DocumentContent,
        lineTax: List<LineTaxFact>,
    ): List<TaxSubtotal> {
        val taxed =
            content.lines.mapIndexed {
                i,
                l,
                ->
                TaxedLine(l.lineNumber, lineTax[i].categoryCode, lineTax[i].rate, l.lineAmount)
            }
        return when (val r = TaxSubtotals.derive(taxed)) {
            is SubtotalResult.Derived -> r.subtotals

            is SubtotalResult.NotDerived -> throw IllegalArgumentException(
                "tax subtotals not derived for lines ${r.lineNumbers}",
            )
        }
    }

    private fun linesOf(
        content: DocumentContent,
        lineTax: List<LineTaxFact>,
    ): List<Map<String, Any>> =
        content.lines.mapIndexed { i, l ->
            val o = linkedMapOf<String, Any>()
            o["lineNumber"] = l.lineNumber
            o["description"] = checkNotNull(l.description)
            o["quantity"] = checkNotNull(l.quantity)
            o["unitPrice"] = checkNotNull(l.unitPrice)
            o["lineAmount"] = l.lineAmount
            o["taxCode"] = lineTax[i].categoryCode
            l.unitCode?.let { o["unitCode"] = it }
            o["taxAmount"] = l.taxAmount ?: TaxSubtotals.taxOf(BigDecimal(l.lineAmount), lineTax[i].rate)
            o
        }

    private fun wrap(
        value: Any,
        classification: String,
    ): Map<String, Any> = linkedMapOf("value" to value, "classification" to classification)

    private fun party(p: ContentParty?): Map<String, Any> {
        val o = linkedMapOf<String, Any>()
        if (p == null) return o
        p.identifierScheme?.let { o["identifierScheme"] = it }
        p.identifierValue?.let { o["identifierValue"] = it }
        p.name?.let { o["name"] = it }
        p.gstn?.let { o["gstn"] = it }
        p.address?.let { a ->
            val ao = linkedMapOf<String, Any>()
            a.line1?.let { ao["line1"] = it }
            a.postalCode?.let { ao["postalCode"] = it }
            a.countryCode?.let { ao["countryCode"] = it }
            o["address"] = ao
        }
        return o
    }

    private fun declared(t: ContentTotals): Map<String, Any> {
        val o = linkedMapOf<String, Any>()
        o["totalExcludingTax"] = t.totalExcludingTax
        o["totalTax"] = t.totalTax
        o["totalIncludingTax"] = t.totalIncludingTax
        t.sgdTotalExcludingTax?.let { o["sgdTotalExcludingTax"] = it }
        t.sgdTotalTax?.let { o["sgdTotalTax"] = it }
        t.sgdTotalIncludingTax?.let { o["sgdTotalIncludingTax"] = it }
        return o
    }

    private fun derived(
        content: DocumentContent,
        taxes: List<BigDecimal>,
    ): Map<String, Any> {
        val net =
            content.lines
                .fold(
                    BigDecimal.ZERO,
                ) { s, l -> s + BigDecimal(l.lineAmount) }
                .setScale(2, RoundingMode.HALF_UP)
        val tax = taxes.fold(BigDecimal.ZERO) { s, t -> s + t }.setScale(2, RoundingMode.HALF_UP)
        return linkedMapOf(
            "totalExcludingTax" to net.toPlainString(),
            "totalTax" to tax.toPlainString(),
            "totalIncludingTax" to (net + tax).toPlainString(),
        )
    }
}
