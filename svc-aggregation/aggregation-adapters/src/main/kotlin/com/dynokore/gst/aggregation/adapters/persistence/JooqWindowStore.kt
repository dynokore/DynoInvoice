package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindow.Companion.AGGREGATE_WINDOW
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.Placement
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * The windows of `aggregation.aggregate_window` (INV-REQ-1316, INV-REQ-435). A window is opened by the first
 * placement into it. The part-closing methods belong to the closing run of INV-T-14464 and fail closed until then.
 */
class JooqWindowStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : WindowRepository {
    override fun place(
        entityRef: String,
        businessUnitRef: String,
        aggregateType: AggregateType,
        date: LocalDate,
        cadence: Cadence,
    ): Placement =
        runner.inTransaction {
            val window = AggregationWindow.containing(cadence, date)
            val tenant = tenants.current().value
            dsl
                .insertInto(AGGREGATE_WINDOW)
                .set(AGGREGATE_WINDOW.WINDOW_ID, UUID.randomUUID())
                .set(AGGREGATE_WINDOW.TENANT_ID, tenant)
                .set(AGGREGATE_WINDOW.ENTITY_REF, entityRef)
                .set(AGGREGATE_WINDOW.BUSINESS_UNIT_REF, businessUnitRef)
                .set(AGGREGATE_WINDOW.AGGREGATE_TYPE, aggregateType.name)
                .set(AGGREGATE_WINDOW.CADENCE, cadence.name)
                .set(AGGREGATE_WINDOW.WINDOW_START, window.start)
                .set(AGGREGATE_WINDOW.WINDOW_END, window.end)
                .onConflictDoNothing()
                .execute()
            val stored =
                checkNotNull(
                    dsl
                        .select(AGGREGATE_WINDOW.STATE)
                        .from(AGGREGATE_WINDOW)
                        .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenant))
                        .and(AGGREGATE_WINDOW.ENTITY_REF.eq(entityRef))
                        .and(AGGREGATE_WINDOW.BUSINESS_UNIT_REF.eq(businessUnitRef))
                        .and(AGGREGATE_WINDOW.AGGREGATE_TYPE.eq(aggregateType.name))
                        .and(AGGREGATE_WINDOW.CADENCE.eq(cadence.name))
                        .and(AGGREGATE_WINDOW.WINDOW_START.eq(window.start))
                        .fetchOne()
                        ?.value1(),
                ) { "the window was just opened or found" }
            Placement(window, WindowState.valueOf(stored))
        }

    override fun openWindowsEndingOnOrBefore(date: LocalDate): List<OpenWindow> =
        runner.inTransaction {
            dsl
                .selectFrom(AGGREGATE_WINDOW)
                .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE_WINDOW.STATE.eq(WindowState.OPEN.name))
                .and(AGGREGATE_WINDOW.WINDOW_END.le(date))
                .orderBy(AGGREGATE_WINDOW.WINDOW_START, AGGREGATE_WINDOW.ENTITY_REF)
                .fetch()
                .map {
                    OpenWindow(
                        entityRef = checkNotNull(it.entityRef),
                        businessUnitRef = checkNotNull(it.businessUnitRef),
                        aggregateType = AggregateType.valueOf(checkNotNull(it.aggregateType)),
                        window = AggregationWindow(checkNotNull(it.windowStart), checkNotNull(it.windowEnd)),
                    )
                }
        }

    override fun isPartClosed(
        window: OpenWindow,
        part: WindowPart,
    ): Boolean = throw UnsupportedOperationException("part closing is built by INV-T-14464")

    override fun closePart(
        window: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    ): Unit = throw UnsupportedOperationException("part closing is built by INV-T-14464")

    override fun recordLate(window: OpenWindow): Unit =
        throw UnsupportedOperationException("the closing run is built by INV-T-14464")
}
