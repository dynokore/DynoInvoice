package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateLine.Companion.AGGREGATE_LINE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * Replaces the lines of [aggregateId] with one line per internal tax code of the transactions it holds (INV-REQ-427
 * item 3, INV-REQ-437). The sum is exact decimal arithmetic (a `numeric` sum), so a credit-note aggregate of refunds
 * 10.00 and 5.00 on one code is one line of 15.00. Call inside a tenant-bound transaction.
 */
internal fun DSLContext.storeLinesOf(
    tenant: UUID,
    aggregateId: UUID,
) {
    deleteFrom(AGGREGATE_LINE)
        .where(AGGREGATE_LINE.TENANT_ID.eq(tenant))
        .and(AGGREGATE_LINE.AGGREGATE_ID.eq(aggregateId))
        .execute()
    select(
        AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE,
        DSL.sum(AGGREGATE_TRANSACTION.AMOUNT.cast(SQLDataType.NUMERIC)).cast(SQLDataType.VARCHAR),
    ).from(AGGREGATE_TRANSACTION)
        .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
        .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(aggregateId))
        .groupBy(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE)
        .orderBy(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE)
        .fetch()
        .forEachIndexed { position, row ->
            insertInto(AGGREGATE_LINE)
                .set(AGGREGATE_LINE.TENANT_ID, tenant)
                .set(AGGREGATE_LINE.AGGREGATE_ID, aggregateId)
                .set(AGGREGATE_LINE.POSITION, position)
                .set(AGGREGATE_LINE.INTERNAL_TAX_CODE, checkNotNull(row.value1()))
                .set(AGGREGATE_LINE.AMOUNT, checkNotNull(row.value2()))
                .execute()
        }
}
