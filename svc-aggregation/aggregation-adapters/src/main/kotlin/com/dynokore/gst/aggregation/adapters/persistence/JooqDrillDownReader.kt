package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.application.DrillDownPage
import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.gst.aggregation.application.DrillDownReader
import com.dynokore.gst.aggregation.application.RetainedTransaction
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * The retained underlying transactions of an aggregate (INV-REQ-414), read by keyset on the documented total order
 * `underlyingRef`, then `transactionRef`, both ascending (API-18); the comparison of the keyset and the ORDER BY use
 * the same column collation, so no item is skipped or repeated between pages. There is no offset.
 */
class JooqDrillDownReader(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : DrillDownReader {
    override fun page(
        aggregateId: UUID,
        after: DrillDownPosition?,
        limit: Int,
    ): DrillDownPage? =
        runner.inTransaction {
            val tenant = tenants.current().value
            val aggregate =
                dsl
                    .select(AGGREGATE.CREDIT_NOTE, AGGREGATE.SUPPLEMENTARY)
                    .from(AGGREGATE)
                    .where(AGGREGATE.TENANT_ID.eq(tenant))
                    .and(AGGREGATE.AGGREGATE_ID.eq(aggregateId))
                    .fetchOne() ?: return@inTransaction null
            val held = held(tenant, aggregateId)
            val rows = rows(held, after, limit)
            val items = rows.take(limit)
            DrillDownPage(
                aggregateId = aggregateId,
                underlyingCount = dsl.fetchCount(AGGREGATE_TRANSACTION, held),
                isCreditNote = checkNotNull(aggregate.value1()),
                isSupplementary = checkNotNull(aggregate.value2()),
                subtotalsByTaxCode = dsl.subtotalsOf(tenant, aggregateId),
                items = items,
                next = items.lastOrNull()?.takeIf { rows.size > limit }?.let { position(it) },
            )
        }

    private fun held(
        tenant: UUID,
        aggregateId: UUID,
    ): Condition = AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant).and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(aggregateId))

    private fun position(item: RetainedTransaction) = DrillDownPosition(item.underlyingRef, item.transactionRef)

    /** One row more than [limit] is read: its presence is what tells that a next page exists. */
    private fun rows(
        held: Condition,
        after: DrillDownPosition?,
        limit: Int,
    ): List<RetainedTransaction> {
        val underlying = AGGREGATE_TRANSACTION.UNDERLYING_REF
        val reference = AGGREGATE_TRANSACTION.TRANSACTION_REF
        val later =
            after?.let {
                underlying.gt(it.underlyingRef).or(underlying.eq(it.underlyingRef).and(reference.gt(it.transactionRef)))
            } ?: DSL.noCondition()
        return dsl
            .select(
                underlying,
                reference,
                AGGREGATE_TRANSACTION.AMOUNT,
                AGGREGATE_TRANSACTION.CLAIMANT_REFERENCE,
                AGGREGATE_TRANSACTION.CLAIMABLE_INPUT_TAX,
            )
            .from(AGGREGATE_TRANSACTION)
            .where(held)
            .and(later)
            .orderBy(underlying, reference)
            .limit(limit + 1)
            .fetch()
            .map {
                RetainedTransaction(
                    underlyingRef = checkNotNull(it.value1()),
                    transactionRef = checkNotNull(it.value2()),
                    amount = checkNotNull(it.value3()),
                    claimantReference = it.value4(),
                    claimableInputTax = it.value5(),
                )
            }
    }
}
