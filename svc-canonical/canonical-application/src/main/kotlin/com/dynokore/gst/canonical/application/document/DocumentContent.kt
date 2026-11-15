package com.dynokore.gst.canonical.application.document

import java.math.BigDecimal

/** The content of a recordDocument request that the canonical document text carries (INV-AC-21599). */
data class DocumentContent(
    val currency: String,
    val supplier: ContentParty?,
    val buyer: ContentParty?,
    /** Index-aligned with the lines of [RecordDocumentCommand]. */
    val lines: List<ContentLine>,
    val declaredTotals: ContentTotals?,
)

data class ContentParty(
    val identifierScheme: String?,
    val identifierValue: String?,
    val name: String?,
    val gstn: String?,
    val address: ContentAddress?,
)

data class ContentAddress(
    val line1: String?,
    val postalCode: String?,
    val countryCode: String?,
)

data class ContentLine(
    val lineNumber: Int,
    val description: String?,
    val quantity: BigDecimal?,
    val unitPrice: String?,
    val lineAmount: String,
    val unitCode: String?,
    val taxAmount: String?,
)

/** The totals the caller declared; validated elsewhere and never recomputed. */
data class ContentTotals(
    val totalExcludingTax: String,
    val totalTax: String,
    val totalIncludingTax: String,
    val sgdTotalExcludingTax: String? = null,
    val sgdTotalTax: String? = null,
    val sgdTotalIncludingTax: String? = null,
)

/** The tax category code and rate (percent, e.g. "9.00") that the rules answer gave one line, in line order. */
data class LineTaxFact(
    val categoryCode: String,
    val rate: String,
)
