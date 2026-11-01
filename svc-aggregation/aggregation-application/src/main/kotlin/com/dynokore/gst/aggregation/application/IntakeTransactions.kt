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
}

/**
 * Stores dated transactions in the window of each transaction's own date, never the day of the intake. A repeat of
 * (entityRef, sourceKey, sourceRevision) returns the original result (INV-REQ-434: "A replay returns the original.").
 */
class IntakeTransactions(
    private val cadences: CadenceSource,
    private val windows: WindowRepository,
    private val transactions: TransactionRepository,
) {
    fun execute(command: TransactionsCommand): TransactionsOutcome {
        transactions.find(command.entityRef, command.sourceKey, command.sourceRevision)?.let {
            return TransactionsOutcome.Replayed(it)
        }
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
