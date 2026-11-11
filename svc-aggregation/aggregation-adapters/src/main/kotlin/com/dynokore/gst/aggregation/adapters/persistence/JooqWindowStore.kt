package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindow.Companion.AGGREGATE_WINDOW
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindowPart.Companion.AGGREGATE_WINDOW_PART
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The windows of `aggregation.aggregate_window` (INV-REQ-1316, INV-REQ-435). A window is opened by the first
 * placement into it. A part of a closed window is recorded by [closePart] (INV-T-14463); [recordLate] belongs to
 * INV-T-14464 and fails closed until then.
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
    ): Boolean =
        runner.inTransaction {
            val windowId = windowId(window) ?: return@inTransaction false
            dsl.fetchExists(
                dsl
                    .selectFrom(AGGREGATE_WINDOW_PART)
                    .where(AGGREGATE_WINDOW_PART.TENANT_ID.eq(tenants.current().value))
                    .and(AGGREGATE_WINDOW_PART.WINDOW_ID.eq(windowId))
                    .and(AGGREGATE_WINDOW_PART.PART_START.eq(part.start)),
            )
        }

    /**
     * Records the part with its aggregate and hands the transactions dated inside the part to that aggregate. The
     * window turns CLOSED once its parts cover every day of it. A second call for the same part changes nothing.
     */
    override fun closePart(
        window: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    ) {
        runner.inTransaction {
            val tenant = tenants.current().value
            val windowId = checkNotNull(windowId(window)) { "the window of a part being closed exists" }
            val inserted =
                dsl
                    .insertInto(AGGREGATE_WINDOW_PART)
                    .set(AGGREGATE_WINDOW_PART.TENANT_ID, tenant)
                    .set(AGGREGATE_WINDOW_PART.WINDOW_ID, windowId)
                    .set(AGGREGATE_WINDOW_PART.PART_START, part.start)
                    .set(AGGREGATE_WINDOW_PART.PART_END, part.end)
                    .set(AGGREGATE_WINDOW_PART.PERIOD_REF, part.periodRef)
                    .set(AGGREGATE_WINDOW_PART.AGGREGATE_ID, aggregateId)
                    .onConflictDoNothing()
                    .execute()
            if (inserted == 0) return@inTransaction
            dsl
                .update(AGGREGATE_TRANSACTION)
                .set(AGGREGATE_TRANSACTION.AGGREGATE_ID, aggregateId)
                .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
                .and(AGGREGATE_TRANSACTION.ENTITY_REF.eq(window.entityRef))
                .and(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF.eq(window.businessUnitRef))
                .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(window.aggregateType.name))
                .and(AGGREGATE_TRANSACTION.WINDOW_START.eq(window.window.start))
                .and(AGGREGATE_TRANSACTION.WINDOW_END.eq(window.window.end))
                .and(AGGREGATE_TRANSACTION.TRANSACTION_DATE.between(part.start, part.end))
                .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.isNull)
                .execute()
            closeWindowIfCovered(window, windowId)
        }
    }

    private fun closeWindowIfCovered(
        window: OpenWindow,
        windowId: UUID,
    ) {
        val tenant = tenants.current().value
        val covered =
            dsl
                .select(AGGREGATE_WINDOW_PART.PART_START, AGGREGATE_WINDOW_PART.PART_END)
                .from(AGGREGATE_WINDOW_PART)
                .where(AGGREGATE_WINDOW_PART.TENANT_ID.eq(tenant))
                .and(AGGREGATE_WINDOW_PART.WINDOW_ID.eq(windowId))
                .fetch()
                .sumOf { ChronoUnit.DAYS.between(it.value1(), it.value2()) + 1 }
        if (covered >= ChronoUnit.DAYS.between(window.window.start, window.window.end) + 1) {
            dsl
                .update(AGGREGATE_WINDOW)
                .set(AGGREGATE_WINDOW.STATE, WindowState.CLOSED.name)
                .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenant))
                .and(AGGREGATE_WINDOW.WINDOW_ID.eq(windowId))
                .and(AGGREGATE_WINDOW.STATE.eq(WindowState.OPEN.name))
                .execute()
        }
    }

    /** The id of the row of [window]; the pair of dates fixes the cadence, as no two cadences share both. */
    private fun windowId(window: OpenWindow): UUID? =
        dsl
            .select(AGGREGATE_WINDOW.WINDOW_ID)
            .from(AGGREGATE_WINDOW)
            .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenants.current().value))
            .and(AGGREGATE_WINDOW.ENTITY_REF.eq(window.entityRef))
            .and(AGGREGATE_WINDOW.BUSINESS_UNIT_REF.eq(window.businessUnitRef))
            .and(AGGREGATE_WINDOW.AGGREGATE_TYPE.eq(window.aggregateType.name))
            .and(AGGREGATE_WINDOW.WINDOW_START.eq(window.window.start))
            .and(AGGREGATE_WINDOW.WINDOW_END.eq(window.window.end))
            .fetchOne()
            ?.value1()

    override fun recordLate(window: OpenWindow): Unit =
        throw UnsupportedOperationException("the closing run is built by INV-T-14464")
}
