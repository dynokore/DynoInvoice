package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType

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
 */
class IntakeTransactions(
    private val cadences: CadenceSource,
    private val windows: WindowRepository,
    private val transactions: TransactionRepository,
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

    private fun create(command: TransactionsCommand): TransactionsOutcome {
        val placed =
            command.transactions.map { transaction ->
                val date = transaction.transactionDate
                val cadence = cadences.cadence(command.entityRef, command.aggregateType, date)
                PlacedTransaction(
                    transaction,
                    windows.place(command.entityRef, command.businessUnitRef, command.aggregateType, date, cadence),
                )
            }
        transactions.store(command.entityRef, command.sourceKey, command.sourceRevision, placed)
        return TransactionsOutcome.Created(placed)
    }
}
