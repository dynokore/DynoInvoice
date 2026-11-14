package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.ReplacedReceipt.Companion.REPLACED_RECEIPT
import com.dynokore.gst.aggregation.application.ReplacedReceiptOutcome
import com.dynokore.gst.aggregation.application.ReplacedReceiptStore
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * The exclusions of `aggregation.replaced_receipt` (INV-REQ-440). The receipt number of the replaced STI receipt is the
 * `transactionRef` of the STI transaction that the feed delivered; every read and write is scoped to the entity.
 */
class JooqReplacedReceiptStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val store: JooqAggregateStore,
) : ReplacedReceiptStore {
    override fun recorded(
        entityRef: String,
        receiptNumber: String,
    ): ReplacedReceiptOutcome? =
        runner.inTransaction {
            dsl
                .select(REPLACED_RECEIPT.OUTCOME)
                .from(REPLACED_RECEIPT)
                .where(REPLACED_RECEIPT.TENANT_ID.eq(tenants.current().value))
                .and(REPLACED_RECEIPT.ENTITY_REF.eq(entityRef))
                .and(REPLACED_RECEIPT.RECEIPT_NUMBER.eq(receiptNumber))
                .fetchOne()
                ?.value1()
                ?.let {
                    when (it) {
                        EXCLUDED -> ReplacedReceiptOutcome.Excluded
                        else -> ReplacedReceiptOutcome.NotAggregated
                    }
                }
        }

    override fun holder(
        entityRef: String,
        receiptNumber: String,
    ): StoredAggregate? =
        runner.inTransaction {
            val tenant = tenants.current().value
            dsl
                .selectFrom(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenant))
                .and(AGGREGATE.SUPERSEDED_BY.isNull)
                .and(
                    AGGREGATE.AGGREGATE_ID.`in`(
                        dsl
                            .select(AGGREGATE_TRANSACTION.AGGREGATE_ID)
                            .from(AGGREGATE_TRANSACTION)
                            .where(receiptOf(entityRef, receiptNumber)),
                    ),
                ).forUpdate()
                .fetchOne()
                ?.let(store::stored)
        }

    override fun exclude(
        entityRef: String,
        receiptNumber: String,
        holder: StoredAggregate?,
    ): ReplacedReceiptOutcome =
        runner.inTransaction {
            val tenant = tenants.current().value
            val outcome =
                if (holder == null) ReplacedReceiptOutcome.NotAggregated else ReplacedReceiptOutcome.Excluded
            dsl
                .insertInto(REPLACED_RECEIPT)
                .set(REPLACED_RECEIPT.TENANT_ID, tenant)
                .set(REPLACED_RECEIPT.ENTITY_REF, entityRef)
                .set(REPLACED_RECEIPT.RECEIPT_NUMBER, receiptNumber)
                .set(REPLACED_RECEIPT.OUTCOME, if (holder == null) NOT_AGGREGATED else EXCLUDED)
                .onConflictDoNothing()
                .execute()
            if (holder != null) {
                dsl
                    .update(AGGREGATE_TRANSACTION)
                    .setNull(AGGREGATE_TRANSACTION.AGGREGATE_ID)
                    .where(receiptOf(entityRef, receiptNumber))
                    .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.eq(holder.aggregateId))
                    .execute()
                dsl.storeLinesOf(tenant, holder.aggregateId)
            }
            outcome
        }

    private fun receiptOf(
        entityRef: String,
        receiptNumber: String,
    ) = AGGREGATE_TRANSACTION.TENANT_ID
        .eq(tenants.current().value)
        .and(AGGREGATE_TRANSACTION.ENTITY_REF.eq(entityRef))
        .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(AggregateType.STI.name))
        .and(AGGREGATE_TRANSACTION.TRANSACTION_REF.eq(receiptNumber))

    private companion object {
        const val EXCLUDED = "EXCLUDED"
        const val NOT_AGGREGATED = "NOT_AGGREGATED"
    }
}
