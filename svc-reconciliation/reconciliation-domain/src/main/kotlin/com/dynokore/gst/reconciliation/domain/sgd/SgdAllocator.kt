package com.dynokore.gst.reconciliation.domain.sgd

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Allocates the caller's stated SGD totals to the lines (INV-Q-7226 A). Pure: no I/O, no clock. It recomputes no
 * stated total; the rounding difference goes to the line with the largest absolute `lineAmount` (lowest `lineNumber`
 * on a tie).
 */
object SgdAllocator {
    private const val SGD = "SGD"
    private const val SCALE = 2

    fun allocate(
        currency: String,
        lines: List<AllocationLine>,
        stated: StatedSgd?,
    ): SgdAllocation {
        val ordered = lines.sortedBy { it.lineNumber }
        val reason = if (currency == SGD) null else refusal(lines, stated)
        return when {
            currency == SGD -> {
                SgdAllocation.Allocated(ordered.map { SgdLine(it.lineNumber, it.taxCode, it.lineAmount, it.taxAmount) })
            }

            reason != null -> {
                SgdAllocation.NotReconcilable(reason)
            }

            else -> {
                SgdAllocation.Allocated(spread(ordered, stated!!))
            }
        }
    }

    /** The first fault in the order lines, total, rate. */
    private fun refusal(
        lines: List<AllocationLine>,
        stated: StatedSgd?,
    ): NotReconcilableReason? =
        when {
            lines.isEmpty() -> NotReconcilableReason.NO_LINES
            stated == null -> NotReconcilableReason.MISSING_SGD_TOTAL
            stated.exchangeRate.signum() <= 0 -> NotReconcilableReason.INVALID_EXCHANGE_RATE
            else -> null
        }

    private fun spread(
        ordered: List<AllocationLine>,
        stated: StatedSgd,
    ): List<SgdLine> {
        val rate = stated.exchangeRate
        val taxable = ordered.map { convert(it.lineAmount, rate) }.toMutableList()
        val tax = ordered.map { convert(it.taxAmount, rate) }.toMutableList()
        // maxByOrNull returns the first maximum and `ordered` is by lineNumber, so a tie goes to the lowest lineNumber.
        val target = ordered.indices.maxByOrNull { ordered[it].lineAmount.abs() }!!
        taxable[target] += stated.totalExcludingTax - taxable.fold(BigDecimal.ZERO, BigDecimal::add)
        tax[target] += stated.totalTax - tax.fold(BigDecimal.ZERO, BigDecimal::add)
        return ordered.indices.map { SgdLine(ordered[it].lineNumber, ordered[it].taxCode, taxable[it], tax[it]) }
    }

    private fun convert(
        amount: BigDecimal,
        rate: BigDecimal,
    ): BigDecimal = (amount * rate).setScale(SCALE, RoundingMode.HALF_UP)
}
