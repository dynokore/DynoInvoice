package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.PeriodSplit
import com.dynokore.gst.aggregation.domain.SplitResult
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

data class TransactionsCommand(
    val entityRef: String,
    val businessUnitRef: String,
    val aggregateType: AggregateType,
    val sourceKey: String,
    val sourceRevision: Long,
    val transactions: List<DatedTransaction>,
)

sealed interface TransactionsOutcome {
    val placed: List<PlacedTransaction>

    data class Created(
        override val placed: List<PlacedTransaction>,
    ) : TransactionsOutcome

    data class Replayed(
        override val placed: List<PlacedTransaction>,
    ) : TransactionsOutcome

    /** A revision lower than a stored one of the same entity and sourceKey (ING-002); nothing is stored. */
    data class SourceRevisionLower(
        val highestRevision: Long,
    ) : TransactionsOutcome {
        override val placed: List<PlacedTransaction> = emptyList()
    }
}

/**
 * Stores dated transactions in the window of each transaction's own date, never the day of the intake. A repeat of
 * (entityRef, sourceKey, sourceRevision) returns the original result (INV-REQ-434: "A replay returns the original.");
 * a revision lower than a stored one of the same entity and sourceKey is refused (ING-002) and stores nothing.
 *
 * A transaction dated inside a part that has already closed is stored with no aggregate, and that part is recorded
 * LATE once; only the call that records it publishes AggregateWindowChanged with state LATE, in the same database
 * transaction as the row (transactional outbox). The closed aggregate is never written here (INV-AC-24763).
 */
class IntakeTransactions(
    private val cadences: CadenceSource,
    private val windows: WindowRepository,
    private val transactions: TransactionRepository,
    private val periods: PeriodBoundarySource,
    private val publisher: WindowChangePublisher,
    private val runner: TransactionRunner,
) {
    fun execute(command: TransactionsCommand): TransactionsOutcome {
        val replay = transactions.find(command.entityRef, command.sourceKey, command.sourceRevision)
        val highest = transactions.highestRevision(command.entityRef, command.sourceKey)
        return when {
            replay != null -> TransactionsOutcome.Replayed(replay)
            highest != null && highest > command.sourceRevision -> TransactionsOutcome.SourceRevisionLower(highest)
            else -> create(command)
        }
    }

    private fun create(command: TransactionsCommand): TransactionsOutcome =
        runner.inTransaction {
            val placed =
                command.transactions.map { transaction ->
                    val date = transaction.transactionDate
                    val cadence = cadences.cadence(command.entityRef, command.aggregateType, date)
                    val placement =
                        windows.place(command.entityRef, command.businessUnitRef, command.aggregateType, date, cadence)
                    recordIfLate(command, placement, date)
                    PlacedTransaction(transaction, placement)
                }
            transactions.store(command.entityRef, command.sourceKey, command.sourceRevision, placed)
            TransactionsOutcome.Created(placed)
        }

    private fun recordIfLate(
        command: TransactionsCommand,
        placement: Placement,
        date: LocalDate,
    ) {
        val window =
            OpenWindow(command.entityRef, command.businessUnitRef, command.aggregateType, placement.window)
        val part = partHolding(window, date)
        if (part != null && windows.isPartClosed(window, part) && windows.recordLate(window, part)) {
            publisher.changed(WindowChange(window, part, WindowState.LATE, null))
        }
    }

    /** The part of the window that holds [date], by the split the closing run uses; null when the split is unknown. */
    private fun partHolding(
        window: OpenWindow,
        date: LocalDate,
    ): WindowPart? =
        when (val known = periods.spans(window.entityRef, window.window.start, window.window.end)) {
            PeriodSpans.Unknown -> {
                null
            }

            is PeriodSpans.Known -> {
                when (val split = PeriodSplit.split(window.window, known.spans)) {
                    is SplitResult.Split -> split.parts.firstOrNull { date >= it.start && date <= it.end }
                    is SplitResult.Uncovered -> null
                }
            }
        }
}
