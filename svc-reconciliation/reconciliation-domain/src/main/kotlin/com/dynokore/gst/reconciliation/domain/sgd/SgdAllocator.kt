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
        // the first fault in the order lines, total, rate
        return when {
            currency == SGD -> {
                SgdAllocation.Allocated(ordered.map { SgdLine(it.lineNumber, it.taxCode, it.lineAmount, it.taxAmount) })
            }

            lines.isEmpty() -> {
                SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES)
            }

            stated == null -> {
                SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)
            }

            stated.exchangeRate.signum() <= 0 -> {
                SgdAllocation.NotReconcilable(NotReconcilableReason.INVALID_EXCHANGE_RATE)
            }

            else -> {
                SgdAllocation.Allocated(spread(ordered, stated))
            }
        }
    }

    private fun spread(
        ordered: List<AllocationLine>,
        stated: StatedSgd,
    ): List<SgdLine> {
        val rate = stated.exchangeRate
        val taxable = ordered.map { convert(it.lineAmount, rate) }.toMutableList()
        val tax = ordered.map { convert(it.taxAmount, rate) }.toMutableList()
        // maxWith returns the first maximum and `ordered` is by lineNumber, so a tie goes to the lowest lineNumber.
        val target = ordered.indices.maxWith(compareBy { ordered[it].lineAmount.abs() })
        taxable[target] += stated.totalExcludingTax - taxable.fold(BigDecimal.ZERO, BigDecimal::add)
        tax[target] += stated.totalTax - tax.fold(BigDecimal.ZERO, BigDecimal::add)
        return ordered.indices.map { SgdLine(ordered[it].lineNumber, ordered[it].taxCode, taxable[it], tax[it]) }
    }

    private fun convert(
        amount: BigDecimal,
        rate: BigDecimal,
    ): BigDecimal = (amount * rate).setScale(SCALE, RoundingMode.HALF_UP)
}
