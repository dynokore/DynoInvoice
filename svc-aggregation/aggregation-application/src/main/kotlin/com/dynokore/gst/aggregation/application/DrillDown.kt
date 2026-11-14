package com.dynokore.gst.aggregation.application

import java.util.UUID

/** One retained underlying transaction; [claimantReference] is a reference, never a claimant name (INV-REQ-884). */
data class RetainedTransaction(
    val underlyingRef: String,
    val transactionRef: String,
    val amount: String,
    val claimantReference: String? = null,
    val claimableInputTax: String? = null,
)

/** The subtotal of one internal tax code: the claimable input tax for a PCP aggregate, the amount otherwise. */
data class TaxCodeSubtotal(
    val internalTaxCode: String,
    val amount: String,
)

/** The position after which a page starts: the sort key of the last item of the page before. */
data class DrillDownPosition(
    val underlyingRef: String,
    val transactionRef: String,
)

data class DrillDownPage(
    val aggregateId: UUID,
    /** The declared number of retained transactions across all pages. */
    val underlyingCount: Int,
    val isCreditNote: Boolean,
    val isSupplementary: Boolean,
    val subtotalsByTaxCode: List<TaxCodeSubtotal>,
    val items: List<RetainedTransaction>,
    /** The position of the last item when more items follow; null on the last page. */
    val next: DrillDownPosition?,
)

interface DrillDownReader {
    /**
     * The page of the retained transactions of the aggregate in the order `underlyingRef`, then `transactionRef`,
     * ascending; null when the tenant has no such aggregate.
     */
    fun page(
        aggregateId: UUID,
        after: DrillDownPosition?,
        limit: Int,
    ): DrillDownPage?
}

/** The drill-down from an aggregate to its retained underlying transactions (INV-REQ-414). */
class GetAggregateDrillDown(
    private val reader: DrillDownReader,
) {
    fun execute(
        aggregateId: UUID,
        after: DrillDownPosition?,
        limit: Int,
    ): DrillDownPage? = reader.page(aggregateId, after, limit)
}
