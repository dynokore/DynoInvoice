package com.dynokore.gst.consoleBff.adapters.duedates

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.PERIOD_DUE_DATE
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateStore
import com.dynokore.gst.consoleBff.application.duedates.PeriodKey
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.UUID

/**
 * `console_bff.period_due_date` through the jOOQ DSL (TPL-11). Isolation is the forced row level security of the
 * table under the caller's signed tenant binding; every statement also names the tenant (defence in depth, NIST SP
 * 800-53 AC-3). Writes are idempotent and version-ordered (event-carried state transfer).
 */
class JooqPeriodDueDateStore(
    private val dsl: DSLContext,
) : PeriodDueDateStore {
    override fun applyIfNewer(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
        version: Long,
    ): Boolean =
        dsl
            .insertInto(PERIOD_DUE_DATE)
            .set(PERIOD_DUE_DATE.TENANT_ID, UUID.fromString(tenantId))
            .set(PERIOD_DUE_DATE.ENTITY_REF, key.entityRef)
            .set(PERIOD_DUE_DATE.PERIOD_REF, key.periodRef)
            .set(PERIOD_DUE_DATE.DUE_DATE, dueDate)
            .set(PERIOD_DUE_DATE.DUE_DATE_VERSION, version)
            .onConflict(PERIOD_DUE_DATE.TENANT_ID, PERIOD_DUE_DATE.ENTITY_REF, PERIOD_DUE_DATE.PERIOD_REF)
            .doUpdate()
            .set(PERIOD_DUE_DATE.DUE_DATE, DSL.excluded(PERIOD_DUE_DATE.DUE_DATE))
            .set(PERIOD_DUE_DATE.DUE_DATE_VERSION, DSL.excluded(PERIOD_DUE_DATE.DUE_DATE_VERSION))
            .where(DSL.excluded(PERIOD_DUE_DATE.DUE_DATE_VERSION).gt(PERIOD_DUE_DATE.DUE_DATE_VERSION))
            .execute() == 1

    override fun putInitialIfAbsent(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
    ): Boolean =
        dsl
            .insertInto(PERIOD_DUE_DATE)
            .set(PERIOD_DUE_DATE.TENANT_ID, UUID.fromString(tenantId))
            .set(PERIOD_DUE_DATE.ENTITY_REF, key.entityRef)
            .set(PERIOD_DUE_DATE.PERIOD_REF, key.periodRef)
            .set(PERIOD_DUE_DATE.DUE_DATE, dueDate)
            .set(PERIOD_DUE_DATE.DUE_DATE_VERSION, 0L)
            .onConflictDoNothing()
            .execute() == 1

    override fun dueDates(
        tenantId: String,
        keys: Set<PeriodKey>,
    ): Map<PeriodKey, LocalDate> {
        if (keys.isEmpty()) return emptyMap()
        val wanted =
            keys.fold(DSL.noCondition()) { acc, k ->
                acc.or(PERIOD_DUE_DATE.ENTITY_REF.eq(k.entityRef).and(PERIOD_DUE_DATE.PERIOD_REF.eq(k.periodRef)))
            }
        return dsl
            .selectFrom(PERIOD_DUE_DATE)
            .where(PERIOD_DUE_DATE.TENANT_ID.eq(UUID.fromString(tenantId)))
            .and(wanted)
            .fetch()
            .associate {
                PeriodKey(it.req(PERIOD_DUE_DATE.ENTITY_REF), it.req(PERIOD_DUE_DATE.PERIOD_REF)) to
                    it.req(PERIOD_DUE_DATE.DUE_DATE)
            }
    }

    private fun <T : Any> Record.req(field: Field<T?>): T = checkNotNull(get(field)) { "${field.name} is null" }
}
