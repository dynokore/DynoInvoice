package com.dynokore.gst.canonical.application.document

import java.math.BigDecimal
import java.math.RoundingMode

/** One line as the subtotal derivation sees it: category code, rate in percent, and net amount (decimal strings). */
data class TaxedLine(
    val lineNumber: Int,
    val categoryCode: String,
    val rate: String,
    val net: String,
)

data class TaxSubtotal(
    val taxCode: String,
    val rate: String,
    val taxableAmount: String,
    val taxAmount: String,
)

sealed interface SubtotalResult {
    data class Derived(
        val subtotals: List<TaxSubtotal>,
    ) : SubtotalResult

    /** At least one line has a blank category code; no partial list is returned and the record is held. */
    data class NotDerived(
        val lineNumbers: List<Int>,
    ) : SubtotalResult
}

/**
 * Tax subtotals per Peppol BIS 3 / EN 16931 BR-CO-17 and the BR-S-08 family: one subtotal per category code and rate,
 * taxable amount = sum of the line nets, tax = taxable x rate / 100 rounded half-up to 2 decimals. BigDecimal only.
 */
object TaxSubtotals {
    private const val SCALE = 2
    private val HUNDRED = BigDecimal(100)

    fun derive(lines: List<TaxedLine>): SubtotalResult {
        val blank = lines.filter { it.categoryCode.isBlank() }.map { it.lineNumber }
        if (blank.isNotEmpty()) return SubtotalResult.NotDerived(blank)
        val groups = LinkedHashMap<Pair<String, BigDecimal>, MutableList<TaxedLine>>()
        lines.forEach {
            groups
                .getOrPut(
                    it.categoryCode to percent(it.rate).stripTrailingZeros(),
                ) { mutableListOf() }
                .add(it)
        }
        return SubtotalResult.Derived(
            groups.map { (key, members) ->
                val taxable = members.fold(BigDecimal.ZERO) { sum, line -> sum + BigDecimal(line.net) }
                TaxSubtotal(
                    taxCode = key.first,
                    rate = members.first().rate,
                    taxableAmount = taxable.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(),
                    taxAmount = taxOf(taxable, members.first().rate),
                )
            },
        )
    }

    /** net x rate / 100 rounded half-up to 2 decimals, as a plain decimal string. */
    fun taxOf(
        net: BigDecimal,
        rate: String,
    ): String =
        net
            .multiply(percent(rate))
            .divide(HUNDRED)
            .setScale(SCALE, RoundingMode.HALF_UP)
            .toPlainString()

    private fun percent(rate: String): BigDecimal = BigDecimal(rate)
}
