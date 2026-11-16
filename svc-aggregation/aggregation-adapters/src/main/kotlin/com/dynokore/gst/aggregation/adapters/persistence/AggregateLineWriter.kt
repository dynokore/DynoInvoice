package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateLine.Companion.AGGREGATE_LINE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.ReplacedReceipt.Companion.REPLACED_RECEIPT
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.TaxCodeSubtotal
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.WindowPart
import org.jooq.Condition
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
    subtotalsWithTaxableOf(tenant, aggregateId)
        .forEachIndexed { position, row ->
            insertInto(AGGREGATE_LINE)
                .set(AGGREGATE_LINE.TENANT_ID, tenant)
                .set(AGGREGATE_LINE.AGGREGATE_ID, aggregateId)
                .set(AGGREGATE_LINE.POSITION, position)
                .set(AGGREGATE_LINE.INTERNAL_TAX_CODE, row.internalTaxCode)
                .set(AGGREGATE_LINE.AMOUNT, row.amount)
                .set(AGGREGATE_LINE.TAXABLE_AMOUNT, row.taxableAmount)
                .execute()
        }
}

/** A code's amount sum and, for PCP only, its taxable sum (null unless every row of the code states one). */
internal data class CodeSums(
    val internalTaxCode: String,
    val amount: String,
    val taxableAmount: String?,
)

/**
 * [subtotalsOf] plus the taxable amount per code (INV-REQ-437): for a PCP aggregate the exact `numeric` sum of
 * `taxable_amount` when the count of rows equals the count of non-null values, otherwise null; null for POS and STI.
 */
internal fun DSLContext.subtotalsWithTaxableOf(
    tenant: UUID,
    aggregateId: UUID,
): List<CodeSums> {
    val taxable =
        select(
            AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE,
            DSL
                .`when`(
                    DSL.count().eq(DSL.count(AGGREGATE_TRANSACTION.TAXABLE_AMOUNT)),
                    DSL.sum(AGGREGATE_TRANSACTION.TAXABLE_AMOUNT.cast(SQLDataType.NUMERIC)).cast(SQLDataType.VARCHAR),
                ).otherwise(DSL.inline(null as String?)),
        ).from(AGGREGATE_TRANSACTION)
            .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(aggregateId))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(AggregateType.PCP.name))
            .groupBy(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE)
            .fetch()
            .associate { checkNotNull(it.value1()) to it.value2() }
    return subtotalsOf(tenant, aggregateId).map { CodeSums(it.internalTaxCode, it.amount, taxable[it.internalTaxCode]) }
}

/**
 * The sum per internal tax code of the transactions [aggregateId] holds, in code order: the claimable input tax for a
 * PCP transaction (INV-REQ-441: the ERP applied the entity's apportionment mode), the amount for any other.
 */
internal fun DSLContext.subtotalsOf(
    tenant: UUID,
    aggregateId: UUID,
): List<TaxCodeSubtotal> {
    val counted =
        DSL
            .`when`(
                AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(AggregateType.PCP.name),
                AGGREGATE_TRANSACTION.CLAIMABLE_INPUT_TAX,
            ).otherwise(AGGREGATE_TRANSACTION.AMOUNT)
    return select(
        AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE,
        DSL.sum(counted.cast(SQLDataType.NUMERIC)).cast(SQLDataType.VARCHAR),
    ).from(AGGREGATE_TRANSACTION)
        .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
        .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(aggregateId))
        .groupBy(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE)
        .orderBy(AGGREGATE_TRANSACTION.INTERNAL_TAX_CODE)
        .fetch()
        .map { TaxCodeSubtotal(checkNotNull(it.value1()), checkNotNull(it.value2())) }
}

/** The refunds and returns of [part] of [window] that no aggregate holds yet and that no full tax invoice replaced. */
internal fun creditNoteTransactions(
    window: OpenWindow,
    part: WindowPart,
    tenant: UUID,
): Condition =
    AGGREGATE_TRANSACTION.TENANT_ID
        .eq(tenant)
        .and(AGGREGATE_TRANSACTION.ENTITY_REF.eq(window.entityRef))
        .and(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF.eq(window.businessUnitRef))
        .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(window.aggregateType.name))
        .and(AGGREGATE_TRANSACTION.WINDOW_START.eq(window.window.start))
        .and(AGGREGATE_TRANSACTION.WINDOW_END.eq(window.window.end))
        .and(AGGREGATE_TRANSACTION.TRANSACTION_DATE.between(part.start, part.end))
        .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.isNull)
        .and(AGGREGATE_TRANSACTION.CREDIT_NOTE.eq(true))
        .and(notReplaced(tenant))

/** A receipt that a full tax invoice replaced is never aggregated (INV-REQ-440). */
internal fun notReplaced(tenant: UUID): Condition =
    DSL.notExists(
        DSL
            .selectOne()
            .from(REPLACED_RECEIPT)
            .where(REPLACED_RECEIPT.TENANT_ID.eq(tenant))
            .and(REPLACED_RECEIPT.ENTITY_REF.eq(AGGREGATE_TRANSACTION.ENTITY_REF))
            .and(REPLACED_RECEIPT.RECEIPT_NUMBER.eq(AGGREGATE_TRANSACTION.TRANSACTION_REF))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(AggregateType.STI.name)),
    )
