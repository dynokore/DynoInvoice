package com.dynokore.gst.reconciliation.domain.sgd

import java.math.BigDecimal

/** One document line as the read model holds it, in the document currency. */
data class AllocationLine(
    val lineNumber: Int,
    val lineAmount: BigDecimal,
    val taxAmount: BigDecimal,
    val taxCode: String,
)

/** The SGD totals the caller states for the document and its exchange rate (`exchangeRate.value`). */
data class StatedSgd(
    val totalExcludingTax: BigDecimal,
    val totalTax: BigDecimal,
    val exchangeRate: BigDecimal,
)

/** A line with its allocated SGD amounts. */
data class SgdLine(
    val lineNumber: Int,
    val taxCode: String,
    val taxableAmountSgd: BigDecimal,
    val taxAmountSgd: BigDecimal,
)

/** Why a document is refused by the allocation; the run reports it as not reconcilable (INV-Q-7226 A). */
enum class NotReconcilableReason {
    NO_LINES,
    MISSING_SGD_TOTAL,
    INVALID_EXCHANGE_RATE,
}

sealed interface SgdAllocation {
    /** The lines ordered by `lineNumber`; for a foreign document they sum exactly to the stated SGD totals. */
    data class Allocated(
        val lines: List<SgdLine>,
    ) : SgdAllocation

    data class NotReconcilable(
        val reason: NotReconcilableReason,
    ) : SgdAllocation
}
