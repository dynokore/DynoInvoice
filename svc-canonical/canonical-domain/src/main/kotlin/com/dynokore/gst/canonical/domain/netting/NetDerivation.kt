package com.dynokore.gst.canonical.domain.netting

import com.dynokore.gst.canonical.domain.document.Direction
import java.math.BigDecimal
import java.util.UUID

/** One line of a net document; amounts have at most 2 fractional digits, `unitPrice` up to 6. */
data class NetLine(
    val lineNumber: Int,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineAmount: BigDecimal,
    val taxCode: String,
    val taxAmount: BigDecimal,
)

data class NetSubtotal(
    val taxCode: String,
    val taxableAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

/** The stated totals of a document; the SGD ones are null when the document does not state them. */
data class NetTotals(
    val excludingTax: BigDecimal,
    val tax: BigDecimal,
    val includingTax: BigDecimal,
    val sgdExcludingTax: BigDecimal? = null,
    val sgdTax: BigDecimal? = null,
    val sgdIncludingTax: BigDecimal? = null,
)

/** The original (current version) whose net is derived. */
data class NetBase(
    val entityId: UUID,
    val direction: Direction,
    val documentFunction: DocumentFunction,
    val currency: String,
    val exchangeRate: BigDecimal?,
    val lines: List<NetLine>,
    val subtotals: List<NetSubtotal>,
    val totals: NetTotals,
)

data class NetCreditNote(
    val docUuid: UUID,
    val version: Int,
    val precedingDocUuid: UUID?,
    val entityId: UUID,
    val direction: Direction,
    val documentFunction: DocumentFunction,
    val currency: String,
    val exchangeRate: BigDecimal?,
    val lines: List<NetLine>,
    val subtotals: List<NetSubtotal>,
    val totals: NetTotals,
)

/**
 * The names equal the reason enum of `canonical/schemas/net-derivation-refused-payload.schema.json`. BASE_CHANGED and
 * DOCUMENT_NOT_FOUND are returned by the application, never by [NetDerivation.derive].
 */
enum class NetRefusal {
    NOT_A_CREDIT_NOTE,
    OTHER_ENTITY,
    CURRENCY_MISMATCH,
    NOT_PRECEDING,
    BASE_CHANGED,
    DOCUMENT_NOT_FOUND,
    NET_NOT_POSITIVE,
}

sealed interface NetResult

/** [creditNotes]: the netted credit notes in `docUuid` order. */
data class Derived(
    val lines: List<NetLine>,
    val subtotals: List<NetSubtotal>,
    val totals: NetTotals,
    val creditNotes: List<NetCreditNote>,
) : NetResult

data class Refused(
    val reason: NetRefusal,
) : NetResult

/**
 * INV-REQ-558 behavior 6: the net of an original and its credit notes, base minus credit notes. Pure: it reads only
 * its arguments and keeps no state. Stated totals are subtracted, never recomputed from the lines (FR-M.7). All
 * arithmetic is exact BigDecimal with scale 2.
 */
object NetDerivation {
    private const val SCALE = 2

    fun derive(
        originalDocUuid: UUID,
        base: NetBase,
        creditNotes: List<NetCreditNote>,
    ): NetResult {
        require(creditNotes.isNotEmpty()) { "creditNotes is empty" }
        require(creditNotes.map { it.docUuid }.toSet().size == creditNotes.size) { "creditNotes repeats a docUuid" }
        requireScale(base.lines, base.subtotals, base.totals)
        creditNotes.forEach { requireScale(it.lines, it.subtotals, it.totals) }
        val notes = creditNotes.sortedBy { it.docUuid }
        val refusal = refusal(originalDocUuid, base, notes)
        if (refusal != null) return Refused(refusal)
        val net =
            Derived(
                base.lines + adjustments(base, notes),
                netSubtotals(base, notes),
                netTotals(
                    base.totals,
                    notes.map {
                        it.totals
                    },
                ),
                notes,
            )
        return if (positive(net.lines, net.subtotals, net.totals)) net else Refused(NetRefusal.NET_NOT_POSITIVE)
    }

    private fun refusal(
        original: UUID,
        base: NetBase,
        notes: List<NetCreditNote>,
    ): NetRefusal? =
        when {
            notes.any { it.documentFunction != DocumentFunction.CREDIT_NOTE } -> NetRefusal.NOT_A_CREDIT_NOTE
            notes.any { it.entityId != base.entityId || it.direction != base.direction } -> NetRefusal.OTHER_ENTITY
            notes.any { it.precedingDocUuid != original } -> NetRefusal.NOT_PRECEDING
            notes.any { currencyDiffers(base, it) } -> NetRefusal.CURRENCY_MISMATCH
            else -> null
        }

    private fun currencyDiffers(
        base: NetBase,
        note: NetCreditNote,
    ): Boolean {
        val a = base.totals
        val b = note.totals
        val sgdOneSided =
            (a.sgdExcludingTax == null) != (b.sgdExcludingTax == null) ||
                (a.sgdTax == null) != (b.sgdTax == null) ||
                (a.sgdIncludingTax == null) != (b.sgdIncludingTax == null)
        val rateDiffers =
            (base.exchangeRate == null) != (note.exchangeRate == null) ||
                (base.exchangeRate != null && base.exchangeRate.compareTo(note.exchangeRate) != 0)
        return base.currency != note.currency || rateDiffers || sgdOneSided
    }

    private fun adjustments(
        base: NetBase,
        notes: List<NetCreditNote>,
    ): List<NetLine> {
        var next = (base.lines.maxOfOrNull { it.lineNumber } ?: 0) + 1
        return notes.flatMap { note -> note.lines.sortedBy { it.lineNumber } }.map { line ->
            NetLine(
                lineNumber = next++,
                quantity = BigDecimal.ONE,
                unitPrice = line.lineAmount.negate(),
                lineAmount = line.lineAmount.negate(),
                taxCode = line.taxCode,
                taxAmount = line.taxAmount.negate(),
            )
        }
    }

    private fun netSubtotals(
        base: NetBase,
        notes: List<NetCreditNote>,
    ): List<NetSubtotal> {
        val net = LinkedHashMap<String, NetSubtotal>()
        for (s in base.subtotals) net.merge(s.taxCode, s) { x, y -> add(x, y.taxableAmount, y.taxAmount) }
        for (note in notes) {
            for (s in note.subtotals) {
                val negated = NetSubtotal(s.taxCode, s.taxableAmount.negate(), s.taxAmount.negate())
                net.merge(s.taxCode, negated) { x, y -> add(x, y.taxableAmount, y.taxAmount) }
            }
        }
        return net.values.toList()
    }

    private fun add(
        s: NetSubtotal,
        taxable: BigDecimal,
        tax: BigDecimal,
    ) = NetSubtotal(s.taxCode, s.taxableAmount.add(taxable), s.taxAmount.add(tax))

    private fun netTotals(
        base: NetTotals,
        notes: List<NetTotals>,
    ): NetTotals =
        NetTotals(
            excludingTax = notes.fold(base.excludingTax) { acc, t -> acc.subtract(t.excludingTax) },
            tax = notes.fold(base.tax) { acc, t -> acc.subtract(t.tax) },
            includingTax = notes.fold(base.includingTax) { acc, t -> acc.subtract(t.includingTax) },
            sgdExcludingTax = subtractSgd(base.sgdExcludingTax, notes.map { it.sgdExcludingTax }),
            sgdTax = subtractSgd(base.sgdTax, notes.map { it.sgdTax }),
            sgdIncludingTax = subtractSgd(base.sgdIncludingTax, notes.map { it.sgdIncludingTax }),
        )

    // Presence on both sides is checked before (currencyDiffers), so a null here means "not stated on either side".
    private fun subtractSgd(
        base: BigDecimal?,
        notes: List<BigDecimal?>,
    ): BigDecimal? = base?.let { notes.fold(it) { acc, n -> acc.subtract(n) } }

    /** INV-Q open owner question: zero or negative net stays in the single reason NET_NOT_POSITIVE. */
    private fun positive(
        lines: List<NetLine>,
        subtotals: List<NetSubtotal>,
        totals: NetTotals,
    ): Boolean {
        val groupNegative = lines.groupBy { it.taxCode }.values.any { g -> g.sumOf { it.lineAmount }.signum() < 0 }
        return totals.includingTax.signum() > 0 && !groupNegative && subtotals.none { it.taxableAmount.signum() < 0 }
    }

    private fun requireScale(
        lines: List<NetLine>,
        subtotals: List<NetSubtotal>,
        totals: NetTotals,
    ) {
        val amounts =
            lines.flatMap { listOf(it.lineAmount, it.taxAmount) } +
                subtotals.flatMap { listOf(it.taxableAmount, it.taxAmount) } +
                listOfNotNull(
                    totals.excludingTax,
                    totals.tax,
                    totals.includingTax,
                    totals.sgdExcludingTax,
                    totals.sgdTax,
                    totals.sgdIncludingTax,
                )
        require(amounts.all { it.scale() <= SCALE }) { "an amount has more than $SCALE fractional digits" }
    }
}
