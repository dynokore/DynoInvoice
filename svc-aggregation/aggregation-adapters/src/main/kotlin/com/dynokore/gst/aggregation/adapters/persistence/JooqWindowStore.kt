package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindow.Companion.AGGREGATE_WINDOW
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateWindowPart.Companion.AGGREGATE_WINDOW_PART
import com.dynokore.gst.aggregation.application.LatePart
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
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The windows of `aggregation.aggregate_window` (INV-REQ-1316, INV-REQ-435). A window is opened by the first
 * placement into it. A part of a closed window is recorded by [closePart] (INV-T-14463); [recordLate] belongs to
 * INV-T-14464 and fails closed until then.
 */
// The class implements every operation of the WindowRepository port; the late-transaction operations raise the count.
@Suppress("TooManyFunctions")
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

    override fun openWindowsStartingOnOrBefore(date: LocalDate): List<OpenWindow> =
        runner.inTransaction {
            dsl
                .selectFrom(AGGREGATE_WINDOW)
                .where(AGGREGATE_WINDOW.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE_WINDOW.STATE.eq(WindowState.OPEN.name))
                .and(AGGREGATE_WINDOW.WINDOW_START.le(date))
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
                .and(AGGREGATE_TRANSACTION.CREDIT_NOTE.eq(false))
                .and(notReplaced(tenant))
                .execute()
            dsl.storeLinesOf(tenant, aggregateId)
            closeWindowIfCovered(window, windowId)
        }
    }

    override fun hasCreditNotes(
        window: OpenWindow,
        part: WindowPart,
    ): Boolean =
        runner.inTransaction {
            val tenant = tenants.current().value
            dsl.fetchExists(
                dsl.selectOne().from(AGGREGATE_TRANSACTION).where(creditNoteTransactions(window, part, tenant)),
            )
        }

    /** The credit-note aggregate holds the refunds and returns of the part; its sales sibling is untouched. */
    override fun closeCreditNotes(
        window: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    ) {
        runner.inTransaction {
            val tenant = tenants.current().value
            val windowId = checkNotNull(windowId(window)) { "the window of a part being closed exists" }
            dsl
                .update(AGGREGATE_WINDOW_PART)
                .set(AGGREGATE_WINDOW_PART.CREDIT_NOTE_AGGREGATE_ID, aggregateId)
                .where(AGGREGATE_WINDOW_PART.TENANT_ID.eq(tenant))
                .and(AGGREGATE_WINDOW_PART.WINDOW_ID.eq(windowId))
                .and(AGGREGATE_WINDOW_PART.PART_START.eq(part.start))
                .and(AGGREGATE_WINDOW_PART.CREDIT_NOTE_AGGREGATE_ID.isNull)
                .execute()
            dsl
                .update(AGGREGATE_TRANSACTION)
                .set(AGGREGATE_TRANSACTION.AGGREGATE_ID, aggregateId)
                .where(creditNoteTransactions(window, part, tenant))
                .execute()
            dsl.storeLinesOf(tenant, aggregateId)
        }
    }

    /**
     * The closed parts whose sales aggregate is transmitted and that hold unassigned transactions dated inside the
     * part, one row per part and kind. Read only: nothing of the transmitted aggregate is written (INV-REQ-439).
     */
    override fun closedPartsWithLateTransactions(): List<LatePart> =
        runner.inTransaction {
            val tenant = tenants.current().value
            lateQuery(tenant).fetch().map(::latePartOf)
        }

    private fun lateQuery(tenant: UUID) =
        dsl
            .selectDistinct(
                AGGREGATE_TRANSACTION.ENTITY_REF,
                AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF,
                AGGREGATE_TRANSACTION.AGGREGATE_TYPE,
                AGGREGATE_TRANSACTION.WINDOW_START,
                AGGREGATE_TRANSACTION.WINDOW_END,
                AGGREGATE_WINDOW_PART.PART_START,
                AGGREGATE_WINDOW_PART.PART_END,
                AGGREGATE_WINDOW_PART.PERIOD_REF,
                AGGREGATE_TRANSACTION.CREDIT_NOTE,
            ).from(AGGREGATE_TRANSACTION)
            .join(AGGREGATE_WINDOW)
            .on(AGGREGATE_WINDOW.TENANT_ID.eq(AGGREGATE_TRANSACTION.TENANT_ID))
            .and(AGGREGATE_WINDOW.ENTITY_REF.eq(AGGREGATE_TRANSACTION.ENTITY_REF))
            .and(AGGREGATE_WINDOW.BUSINESS_UNIT_REF.eq(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF))
            .and(AGGREGATE_WINDOW.AGGREGATE_TYPE.eq(AGGREGATE_TRANSACTION.AGGREGATE_TYPE))
            .and(AGGREGATE_WINDOW.WINDOW_START.eq(AGGREGATE_TRANSACTION.WINDOW_START))
            .and(AGGREGATE_WINDOW.WINDOW_END.eq(AGGREGATE_TRANSACTION.WINDOW_END))
            .join(AGGREGATE_WINDOW_PART)
            .on(AGGREGATE_WINDOW_PART.TENANT_ID.eq(AGGREGATE_WINDOW.TENANT_ID))
            .and(AGGREGATE_WINDOW_PART.WINDOW_ID.eq(AGGREGATE_WINDOW.WINDOW_ID))
            .and(
                AGGREGATE_TRANSACTION.TRANSACTION_DATE
                    .between(AGGREGATE_WINDOW_PART.PART_START, AGGREGATE_WINDOW_PART.PART_END),
            ).join(AGGREGATE)
            .on(AGGREGATE.TENANT_ID.eq(AGGREGATE_WINDOW_PART.TENANT_ID))
            .and(AGGREGATE.AGGREGATE_ID.eq(AGGREGATE_WINDOW_PART.AGGREGATE_ID))
            .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.isNull)
            .and(AGGREGATE.TRANSMITTED_AT.isNotNull)
            .and(notReplaced(tenant))
            .orderBy(
                AGGREGATE_WINDOW_PART.PART_START,
                AGGREGATE_TRANSACTION.ENTITY_REF,
                AGGREGATE_TRANSACTION.CREDIT_NOTE,
            )

    private fun latePartOf(row: Record): LatePart =
        LatePart(
            window =
                OpenWindow(
                    entityRef = checkNotNull(row.get(AGGREGATE_TRANSACTION.ENTITY_REF)),
                    businessUnitRef = checkNotNull(row.get(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF)),
                    aggregateType = AggregateType.valueOf(checkNotNull(row.get(AGGREGATE_TRANSACTION.AGGREGATE_TYPE))),
                    window =
                        AggregationWindow(
                            checkNotNull(row.get(AGGREGATE_TRANSACTION.WINDOW_START)),
                            checkNotNull(row.get(AGGREGATE_TRANSACTION.WINDOW_END)),
                        ),
                ),
            part =
                WindowPart(
                    checkNotNull(row.get(AGGREGATE_WINDOW_PART.PART_START)),
                    checkNotNull(row.get(AGGREGATE_WINDOW_PART.PART_END)),
                    checkNotNull(row.get(AGGREGATE_WINDOW_PART.PERIOD_REF)),
                ),
            isCreditNote = checkNotNull(row.get(AGGREGATE_TRANSACTION.CREDIT_NOTE)),
        )

    /** The aggregates of the late source keys of the part and kind that hold at least one transaction. */
    override fun lateSupplementaryCount(late: LatePart): Int =
        runner.inTransaction {
            val tenant = tenants.current().value
            val prefix = lateKeyPrefix(late)
            dsl
                .select(DSL.countDistinct(AGGREGATE_TRANSACTION.AGGREGATE_ID))
                .from(AGGREGATE_TRANSACTION)
                .join(AGGREGATE)
                .on(AGGREGATE.TENANT_ID.eq(AGGREGATE_TRANSACTION.TENANT_ID))
                .and(AGGREGATE.AGGREGATE_ID.eq(AGGREGATE_TRANSACTION.AGGREGATE_ID))
                .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
                .and(AGGREGATE.ENTITY_REF.eq(late.window.entityRef))
                .and(AGGREGATE.SOURCE_KEY.startsWith(prefix))
                .fetchOne()
                ?.value1() ?: 0
        }

    /**
     * Sets `aggregate_id` of the unassigned transactions of the part and kind to [aggregateId] and stores the lines
     * of that aggregate, in one transaction. Only `aggregate_transaction.aggregate_id` of rows with a null value and
     * the new aggregate's own lines are written; the transmitted aggregate is not touched.
     */
    override fun assignLate(
        late: LatePart,
        aggregateId: UUID,
    ) {
        runner.inTransaction {
            val tenant = tenants.current().value
            dsl
                .update(AGGREGATE_TRANSACTION)
                .set(AGGREGATE_TRANSACTION.AGGREGATE_ID, aggregateId)
                .where(lateTransactions(late, tenant))
                .execute()
            dsl.storeLinesOf(tenant, aggregateId)
        }
    }

    private fun lateKeyPrefix(late: LatePart): String {
        val kind = if (late.isCreditNote) "window-late-credit" else "window-late"
        val w = late.window
        return "$kind:${w.entityRef}:${w.aggregateType}:${late.part.start}:${late.part.end}:"
    }

    private fun lateTransactions(
        late: LatePart,
        tenant: UUID,
    ): Condition =
        AGGREGATE_TRANSACTION.TENANT_ID
            .eq(tenant)
            .and(AGGREGATE_TRANSACTION.ENTITY_REF.eq(late.window.entityRef))
            .and(AGGREGATE_TRANSACTION.BUSINESS_UNIT_REF.eq(late.window.businessUnitRef))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_TYPE.eq(late.window.aggregateType.name))
            .and(AGGREGATE_TRANSACTION.WINDOW_START.eq(late.window.window.start))
            .and(AGGREGATE_TRANSACTION.WINDOW_END.eq(late.window.window.end))
            .and(AGGREGATE_TRANSACTION.TRANSACTION_DATE.between(late.part.start, late.part.end))
            .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.isNull)
            .and(AGGREGATE_TRANSACTION.CREDIT_NOTE.eq(late.isCreditNote))
            .and(notReplaced(tenant))

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
