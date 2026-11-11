package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.DatedTransaction
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.IntakeTransactions
import com.dynokore.gst.aggregation.application.PlacedTransaction
import com.dynokore.gst.aggregation.application.TransactionsCommand
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsReceipt
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsRequest
import com.dynokore.gst.aggregation.domain.AggregateIntakeCheck
import com.dynokore.gst.aggregation.domain.IntakeVerdict
import com.dynokore.gst.aggregation.domain.WindowState
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The dated-transaction intake of the aggregation contract (INV-REQ-435 item 2, INV-REQ-434 items 2 and 3). It extends
 * [IntakeController] because the contract has one `IntakeApi` for both operations, and it is the registered bean of
 * `IntakeApi`. Each transaction is stored in the window of its own date; a repeat of
 * (entityRef, sourceKey, sourceRevision) answers the receipt of the first call and stores nothing.
 */
@RestController
open class TransactionIntakeController(
    intake: IntakeAggregate,
    private val cadences: CadenceSource,
    private val windows: WindowRepository,
    private val transactions: JooqTransactionStore,
) : IntakeController(intake) {
    override fun intakeAggregationTransactions(
        idempotencyKey: UUID,
        aggregationTransactionsRequest: AggregationTransactionsRequest,
    ): ResponseEntity<AggregationTransactionsReceipt> {
        val request = aggregationTransactionsRequest
        val type = (AggregateIntakeCheck.check(request.aggregateType.name) as IntakeVerdict.Accepted).type
        val outcome =
            IntakeTransactions(cadences, windows, transactions.scoped(request.businessUnitRef, type))
                .execute(
                    TransactionsCommand(
                        entityRef = request.entityRef,
                        businessUnitRef = request.businessUnitRef,
                        aggregateType = type,
                        sourceKey = request.sourceKey,
                        sourceRevision = request.sourceRevision.toLong(),
                        transactions =
                            request.transactions.map {
                                DatedTransaction(
                                    transactionRef = it.transactionRef,
                                    transactionDate = it.transactionDate,
                                    internalTaxCode = it.internalTaxCode,
                                    amount = it.amount.toPlainString(),
                                )
                            },
                    ),
                )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            AggregationTransactionsReceipt(
                entityRef = request.entityRef,
                aggregateType = AggregationTransactionsReceipt.AggregateType.valueOf(type.name),
                acceptedCount = outcome.placed.size,
                windowState = receiptState(outcome.placed),
            ),
        )
    }

    /**
     * The stored states of the windows the transactions went to. For a request spanning several windows the state
     * furthest from OPEN is answered (OPEN, CLOSED, LATE in that order; fail-safe: a window is never reported as more
     * open than it is). Coordinator ruling, breakdown tooling/gap14463-gap.md.
     */
    private fun receiptState(placed: List<PlacedTransaction>): AggregationTransactionsReceipt.WindowState {
        val state = placed.map { it.placement.state }.maxByOrNull(WindowState::ordinal) ?: WindowState.OPEN
        return AggregationTransactionsReceipt.WindowState.valueOf(state.name)
    }
}
