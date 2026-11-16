package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import java.time.LocalDate
import java.util.UUID

/** The cadence in force for an entity and category on a business date (INV-REQ-413 item 1, INV-REQ-1316). */
fun interface CadenceSource {
    fun cadence(
        entityRef: String,
        aggregateType: AggregateType,
        businessDate: LocalDate,
    ): Cadence
}

sealed interface PeriodSpans {
    /** The prescribed periods that cover the asked dates (they may leave a gap; the split then fails closed). */
    data class Known(
        val spans: List<PeriodSpan>,
    ) : PeriodSpans

    /** No prescribed period is known for the entity: the window is held, never split on a guess. */
    data object Unknown : PeriodSpans
}

/** The prescribed periods of an entity between two dates, both included. */
fun interface PeriodBoundarySource {
    fun spans(
        entityRef: String,
        from: LocalDate,
        to: LocalDate,
    ): PeriodSpans
}

/** Where an aggregate or a transaction was placed, and the state of that window at placement. */
data class Placement(
    val window: AggregationWindow,
    val state: WindowState,
)

/** An open window of one entity, business unit and category. */
data class OpenWindow(
    val entityRef: String,
    val businessUnitRef: String,
    val aggregateType: AggregateType,
    val window: AggregationWindow,
)

interface WindowRepository {
    /** Places an aggregate or transaction dated [date] in the window of [cadence] holding it; opens it when absent. */
    fun place(
        entityRef: String,
        businessUnitRef: String,
        aggregateType: AggregateType,
        date: LocalDate,
        cadence: Cadence,
    ): Placement

    /**
     * The open windows whose start is on or before [date]; a window stays listed until its closed parts cover all
     * its days.
     */
    fun openWindowsStartingOnOrBefore(date: LocalDate): List<OpenWindow>

    /** Whether [part] of [window] is already closed; a window stays listed as open until all its parts are. */
    fun isPartClosed(
        window: OpenWindow,
        part: WindowPart,
    ): Boolean

    /** Closes [part] of [window] with the aggregate made from it. Closing a closed part again changes nothing. */
    fun closePart(
        window: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    )

    /** Records that [window] was reached after it had closed. */
    fun recordLate(window: OpenWindow)

    /**
     * Whether [part] of [window] holds refunds or returns (transactions of a credit note) that no aggregate holds yet;
     * they form their own aggregate and are never netted with the sales (INV-REQ-438).
     */
    fun hasCreditNotes(
        window: OpenWindow,
        part: WindowPart,
    ): Boolean = false

    /**
     * Hands the credit-note transactions of [part] to the credit-note aggregate [aggregateId], stores its one line
     * per internal tax code and records it on the part row the sales aggregate closed.
     */
    fun closeCreditNotes(
        window: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    ) {}
}

/** One state change of a window part, made by the closing run. */
data class WindowChange(
    val window: OpenWindow,
    val part: WindowPart,
    val state: WindowState,
    val aggregateId: UUID?,
)

/** One call per state change that the closing run makes. */
fun interface WindowChangePublisher {
    fun changed(change: WindowChange)
}

/** A dated transaction of the transaction-level feed (INV-REQ-435 item 2: windows hold the transactions by date). */
data class DatedTransaction(
    val transactionRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val amount: String,
    /** The batch that delivered the transaction (FR-4.9); the first sort key of the drill-down. */
    val underlyingRef: String = "",
    /** A refund or return: its aggregate is a credit-note aggregate (INV-REQ-438). */
    val isCreditNote: Boolean = false,
    /** The reference of an expense claimant, never a name (INV-REQ-884). */
    val claimantReference: String? = null,
    /** PCP only: the claimable input tax as the ERP stated it, summed per internal tax code (INV-REQ-441). */
    val claimableInputTax: String? = null,
    /** PCP only: the tax-exclusive amount on which the claimable input tax is claimed, as the ERP stated it. */
    val taxableAmount: String? = null,
)

data class PlacedTransaction(
    val transaction: DatedTransaction,
    val placement: Placement,
)

interface TransactionRepository {
    fun find(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
    ): List<PlacedTransaction>?

    /** The highest sourceRevision stored for the entity and sourceKey within the tenant; null when none is stored. */
    fun highestRevision(
        entityRef: String,
        sourceKey: String,
    ): Long?

    fun store(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
        placed: List<PlacedTransaction>,
    )
}
