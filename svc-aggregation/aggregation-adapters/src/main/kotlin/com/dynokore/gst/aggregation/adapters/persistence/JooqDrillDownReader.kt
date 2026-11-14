package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.application.DrillDownPage
import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.gst.aggregation.application.DrillDownReader
import com.dynokore.gst.aggregation.application.RetainedTransaction
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * The retained underlying transactions of an aggregate (INV-REQ-414), read by keyset on the documented total order
 * `underlyingRef`, then `transactionRef`, both ascending and compared bytewise (`C` collation) so the order never
 * depends on the database locale (API-18). There is no offset and no total count query beyond the declared count.
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
            val held =
                AGGREGATE_TRANSACTION.TENANT_ID
                    .eq(tenant)
                    .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(aggregateId))
            val count = dsl.fetchCount(AGGREGATE_TRANSACTION, held)
            val underlying = AGGREGATE_TRANSACTION.UNDERLYING_REF.collate(BYTEWISE)
            val reference = AGGREGATE_TRANSACTION.TRANSACTION_REF.collate(BYTEWISE)
            val later =
                after?.let {
                    underlying
                        .gt(
                            it.underlyingRef,
                        ).or(underlying.eq(it.underlyingRef).and(reference.gt(it.transactionRef)))
                } ?: DSL.noCondition()
            val rows =
                dsl
                    .select(
                        AGGREGATE_TRANSACTION.UNDERLYING_REF,
                        AGGREGATE_TRANSACTION.TRANSACTION_REF,
                        AGGREGATE_TRANSACTION.AMOUNT,
                        AGGREGATE_TRANSACTION.CLAIMANT_REFERENCE,
                    ).from(AGGREGATE_TRANSACTION)
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
                        )
                    }
            val items = rows.take(limit)
            DrillDownPage(
                aggregateId = aggregateId,
                underlyingCount = count,
                isCreditNote = checkNotNull(aggregate.value1()),
                isSupplementary = checkNotNull(aggregate.value2()),
                items = items,
                next =
                    items.lastOrNull()?.takeIf { rows.size > limit }?.let {
                        DrillDownPosition(
                            it.underlyingRef,
                            it.transactionRef,
                        )
                    },
            )
        }

    private companion object {
        const val BYTEWISE = "C"
    }
}
