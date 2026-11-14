package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateLine.Companion.AGGREGATE_LINE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindow.Companion.AGGREGATE_WINDOW
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindowPart.Companion.AGGREGATE_WINDOW_PART
import com.dynokore.gst.aggregation.application.HandOffRepository
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/** The hand-off side of `aggregation.aggregate` (INV-REQ-718): what is due to go to submission, and its mark. */
class JooqHandOffStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val store: JooqAggregateStore,
) : HandOffRepository {
    override fun due(entityRef: String): List<StoredAggregate> =
        runner.inTransaction {
            val tenant = tenants.current().value
            val closed = partClosed(tenant).or(windowNotOpen(tenant))
            dsl
                .selectFrom(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenant))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SUPERSEDED_BY.isNull)
                .and(AGGREGATE.HANDED_ON_AT.isNull)
                .and(hasLines(tenant))
                .and(closed)
                .orderBy(AGGREGATE.CREATED_AT, AGGREGATE.SEQUENCE)
                .fetch()
                .map(store::stored)
        }

    override fun markHandedOn(aggregateId: UUID) {
        runner.inTransaction {
            dsl
                .update(AGGREGATE)
                .set(AGGREGATE.HANDED_ON_AT, DSL.currentOffsetDateTime())
                .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE.AGGREGATE_ID.eq(aggregateId))
                .and(AGGREGATE.HANDED_ON_AT.isNull)
                .execute()
        }
    }

    private fun hasLines(tenant: UUID): Condition =
        DSL.exists(
            DSL
                .selectOne()
                .from(AGGREGATE_LINE)
                .where(AGGREGATE_LINE.TENANT_ID.eq(tenant))
                .and(AGGREGATE_LINE.AGGREGATE_ID.eq(AGGREGATE.AGGREGATE_ID)),
        )

    private fun partClosed(tenant: UUID): Condition =
        DSL.exists(
            DSL
                .selectOne()
                .from(AGGREGATE_WINDOW_PART)
                .where(AGGREGATE_WINDOW_PART.TENANT_ID.eq(tenant))
                .and(AGGREGATE_WINDOW_PART.AGGREGATE_ID.eq(AGGREGATE.AGGREGATE_ID)),
        )

    private fun windowNotOpen(tenant: UUID): Condition =
        DSL.exists(
            DSL
                .selectOne()
                .from(AGGREGATE_WINDOW)
                .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenant))
                .and(AGGREGATE_WINDOW.ENTITY_REF.eq(AGGREGATE.ENTITY_REF))
                .and(AGGREGATE_WINDOW.BUSINESS_UNIT_REF.eq(AGGREGATE.BUSINESS_UNIT_REF))
                .and(AGGREGATE_WINDOW.AGGREGATE_TYPE.eq(AGGREGATE.AGGREGATE_TYPE))
                .and(AGGREGATE_WINDOW.WINDOW_START.eq(AGGREGATE.WINDOW_START))
                .and(AGGREGATE_WINDOW.WINDOW_END.eq(AGGREGATE.WINDOW_END))
                .and(AGGREGATE_WINDOW.STATE.ne(WindowState.OPEN.name)),
        )
}
