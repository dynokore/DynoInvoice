package com.dynokore.gst.payload.domain

import java.math.BigDecimal

/** One category-code group of a document: its taxable and tax amount. */
data class TallyGroup(
    val categoryCode: String,
    val taxableAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

/** The document totals compared by the tally. */
data class DocumentTotals(
    val netTotal: BigDecimal,
    val taxTotal: BigDecimal,
    val grossTotal: BigDecimal,
)

/** What the tally compares of one document (INV-REQ-694, INV-Q-6637 A). */
data class TallyBasis(
    val currency: String,
    val groups: List<TallyGroup>,
    val totals: DocumentTotals,
)

/** The kinds of mismatch; a vocabulary, not a field mapping. */
enum class TallyMismatchKind {
    CURRENCY,
    CATEGORY_CODE_SET,
    TAXABLE_AMOUNT,
    TAX_AMOUNT,
    DOCUMENT_TOTAL,
    ORIGINAL_UNKNOWN,
}

/** One difference; `key` is the category code, a total's field name, or null; amounts are plain strings. */
data class TallyMismatch(
    val kind: TallyMismatchKind,
    val key: String?,
    val original: String?,
    val creditNote: String?,
)

sealed interface TallyResult {
    data object Tallies : TallyResult

    data class Mismatches(
        val list: List<TallyMismatch>,
    ) : TallyResult
}

/** The tally of a cancellation credit note against its original (INV-REQ-694, INV-Q-6637 A), a pure function. */
object CancellationTally {
    fun compare(
        original: TallyBasis?,
        creditNote: TallyBasis,
    ): TallyResult {
        if (original == null) {
            return TallyResult.Mismatches(listOf(TallyMismatch(TallyMismatchKind.ORIGINAL_UNKNOWN, null, null, null)))
        }
        val o = sum(original.groups)
        val c = sum(creditNote.groups)
        val found =
            listOfNotNull(currencyMismatch(original, creditNote)) +
                codeSetMismatches(o, c) +
                amountMismatches(o, c) +
                totalMismatches(original.totals, creditNote.totals)
        return if (found.isEmpty()) TallyResult.Tallies else TallyResult.Mismatches(found)
    }

    private fun currencyMismatch(
        original: TallyBasis,
        creditNote: TallyBasis,
    ): TallyMismatch? =
        if (original.currency != creditNote.currency) {
            TallyMismatch(TallyMismatchKind.CURRENCY, null, original.currency, creditNote.currency)
        } else {
            null
        }

    private fun codeSetMismatches(
        o: Map<String, Pair<BigDecimal, BigDecimal>>,
        c: Map<String, Pair<BigDecimal, BigDecimal>>,
    ): List<TallyMismatch> =
        (o.keys + c.keys).toSortedSet().filter { it !in o || it !in c }.map { code ->
            TallyMismatch(
                TallyMismatchKind.CATEGORY_CODE_SET,
                code,
                o[code]?.first?.toPlainString(),
                c[code]?.first?.toPlainString(),
            )
        }

    private fun amountMismatches(
        o: Map<String, Pair<BigDecimal, BigDecimal>>,
        c: Map<String, Pair<BigDecimal, BigDecimal>>,
    ): List<TallyMismatch> =
        o.keys.intersect(c.keys).toSortedSet().flatMap { code ->
            val a = o.getValue(code)
            val b = c.getValue(code)
            listOfNotNull(
                differs(TallyMismatchKind.TAXABLE_AMOUNT, code, a.first, b.first),
                differs(TallyMismatchKind.TAX_AMOUNT, code, a.second, b.second),
            )
        }

    private fun totalMismatches(
        ot: DocumentTotals,
        ct: DocumentTotals,
    ): List<TallyMismatch> =
        listOfNotNull(
            differs(TallyMismatchKind.DOCUMENT_TOTAL, "netTotal", ot.netTotal, ct.netTotal),
            differs(TallyMismatchKind.DOCUMENT_TOTAL, "taxTotal", ot.taxTotal, ct.taxTotal),
            differs(TallyMismatchKind.DOCUMENT_TOTAL, "grossTotal", ot.grossTotal, ct.grossTotal),
        )

    private fun sum(groups: List<TallyGroup>): Map<String, Pair<BigDecimal, BigDecimal>> =
        groups.groupBy { it.categoryCode }.mapValues { (_, g) ->
            Pair(
                g.fold(BigDecimal.ZERO) { acc, x -> acc + x.taxableAmount },
                g.fold(BigDecimal.ZERO) { acc, x -> acc + x.taxAmount },
            )
        }

    private fun differs(
        kind: TallyMismatchKind,
        key: String,
        original: BigDecimal,
        creditNote: BigDecimal,
    ): TallyMismatch? =
        if (original.abs().compareTo(creditNote.abs()) == 0) {
            null
        } else {
            TallyMismatch(kind, key, original.toPlainString(), creditNote.toPlainString())
        }
}
