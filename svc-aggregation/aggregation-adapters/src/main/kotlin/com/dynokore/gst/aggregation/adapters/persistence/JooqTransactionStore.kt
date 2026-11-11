package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.application.DatedTransaction
import com.dynokore.gst.aggregation.application.PlacedTransaction
import com.dynokore.gst.aggregation.application.Placement
import com.dynokore.gst.aggregation.application.TransactionRepository
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * The dated transactions of `aggregation.aggregate_transaction` (INV-REQ-435, INV-REQ-434). A transaction keeps the
 * window of its own date and that window's state at the intake; one call stores all its rows in one transaction.
 * The port [TransactionRepository] carries neither the business unit nor the category, which the closing run needs to
 * find the transactions of a window, so a repository is made per call with [scoped].
 */
class JooqTransactionStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) {
    /** The repository of one intake call: every row it stores carries [businessUnitRef] and [aggregateType]. */
    fun scoped(
        businessUnitRef: String,
        aggregateType: AggregateType,
    ): TransactionRepository = Scoped(businessUnitRef, aggregateType)

    private inner class Scoped(
        private val businessUnitRef: String,
        private val aggregateType: AggregateType,
    ) : TransactionRepository {
        override fun find(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
        ): List<PlacedTransaction>? = this@JooqTransactionStore.find(entityRef, sourceKey, sourceRevision)

        override fun store(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
            placed: List<PlacedTransaction>,
        ) = this@JooqTransactionStore.store(
            Origin(entityRef, businessUnitRef, aggregateType, sourceKey, sourceRevision),
            placed,
        )
    }

    private fun find(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
    ): List<PlacedTransaction>? =
        runner.inTransaction {
            dsl
                .selectFrom(AGGREGATE_TRANSACTION)
                .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE_TRANSACTION.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE_TRANSACTION.SOURCE_KEY.eq(sourceKey))
                .and(AGGREGATE_TRANSACTION.SOURCE_REVISION.eq(sourceRevision))
                .orderBy(AGGREGATE_TRANSACTION.POSITION)
                .fetch()
                .map { row ->
                    PlacedTransaction(
                        DatedTransaction(
                            transactionRef = checkNotNull(row.transactionRef),
                            transactionDate = checkNotNull(row.transactionDate),
                            internalTaxCode = checkNotNull(row.internalTaxCode),
                            amount = checkNotNull(row.amount),
                        ),
                        Placement(
                            AggregationWindow(checkNotNull(row.windowStart), checkNotNull(row.windowEnd)),
                            WindowState.valueOf(checkNotNull(row.windowState)),
                        ),
                    )
                }.ifEmpty { null }
        }

    /** A row already stored under the same key is left as it is: the first intake wins, a repeat stores nothing. */
    private fun store(
        origin: Origin,
        placed: List<PlacedTransaction>,
    ) {
        runner.inTransaction {
            val tenant = tenants.current().value
            placed.forEachIndexed { index, item ->
                dsl
                    .insertInto(AGGREGATE_TRANSACTION)
                    .set(AGGREGATE_TRANSACTION.TENANT_ID, tenant)
                    .set(AGGREGATE_TRANSACTION.ENTITY_REF, origin.entityRef)
                    .set(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF, origin.businessUnitRef)
                    .set(AGGREGATE_TRANSACTION.AGGREGATE_TYPE, origin.aggregateType.name)
                    .set(AGGREGATE_TRANSACTION.SOURCE_KEY, origin.sourceKey)
                    .set(AGGREGATE_TRANSACTION.SOURCE_REVISION, origin.sourceRevision)
                    .set(AGGREGATE_TRANSACTION.TRANSACTION_REF, item.transaction.transactionRef)
                    .set(AGGREGATE_TRANSACTION.POSITION, index)
                    .set(AGGREGATE_TRANSACTION.TRANSACTION_DATE, item.transaction.transactionDate)
                    .set(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE, item.transaction.internalTaxCode)
                    .set(AGGREGATE_TRANSACTION.AMOUNT, item.transaction.amount)
                    .set(AGGREGATE_TRANSACTION.WINDOW_START, item.placement.window.start)
                    .set(AGGREGATE_TRANSACTION.WINDOW_END, item.placement.window.end)
                    .set(AGGREGATE_TRANSACTION.WINDOW_STATE, item.placement.state.name)
                    .onConflictDoNothing()
                    .execute()
            }
        }
    }

    private data class Origin(
        val entityRef: String,
        val businessUnitRef: String,
        val aggregateType: AggregateType,
        val sourceKey: String,
        val sourceRevision: Long,
    )
}
