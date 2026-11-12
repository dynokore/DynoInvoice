package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.period.PeriodContainingReader
import com.dynokore.gst.submission.application.period.PeriodRecord
import com.dynokore.gst.submission.application.period.PeriodRecordStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodRecord as PeriodRecordTable

/**
 * [PeriodRecordStore] and [PeriodContainingReader] over `submission.period_record` (INV-REQ-570). [replace] deletes
 * the entity's rows whose period reference is not in the event, then upserts each period (insert, else update) with
 * the event version, in the caller's transaction, so a failure leaves the previous set intact. Every statement names
 * the tenant and forced row level security applies. Values are never logged.
 */
class JooqPeriodRecordStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : PeriodRecordStore,
    PeriodContainingReader {
    private val p = PeriodRecordTable.PERIOD_RECORD

    override fun highestVersion(
        tenant: TenantId,
        entityRef: String,
    ): Int? =
        runner.inTransaction {
            dsl
                .select(DSL.max(p.VERSION))
                .from(p)
                .where(p.TENANT_ID.eq(tenant.value))
                .and(p.ENTITY_REF.eq(entityRef))
                .fetchOne(0, Int::class.javaObjectType)
        }

    override fun replace(
        tenant: TenantId,
        entityRef: String,
        version: Int,
        periods: List<PeriodRecord>,
    ) {
        runner.inTransaction {
            dsl
                .deleteFrom(p)
                .where(p.TENANT_ID.eq(tenant.value))
                .and(p.ENTITY_REF.eq(entityRef))
                .and(p.PERIOD_REF.notIn(periods.map { it.periodRef }))
                .execute()
            periods.forEach { period ->
                val inserted =
                    dsl
                        .insertInto(p)
                        .set(p.TENANT_ID, tenant.value)
                        .set(p.ENTITY_REF, entityRef)
                        .set(p.PERIOD_REF, period.periodRef)
                        .set(p.PERIOD_START, period.periodStart)
                        .set(p.PERIOD_END, period.periodEnd)
                        .set(p.VERSION, version)
                        .onConflict(p.TENANT_ID, p.ENTITY_REF, p.PERIOD_REF)
                        .doNothing()
                        .execute()
                if (inserted == 0) {
                    dsl
                        .update(p)
                        .set(p.PERIOD_START, period.periodStart)
                        .set(p.PERIOD_END, period.periodEnd)
                        .set(p.VERSION, version)
                        .where(p.TENANT_ID.eq(tenant.value))
                        .and(p.ENTITY_REF.eq(entityRef))
                        .and(p.PERIOD_REF.eq(period.periodRef))
                        .execute()
                }
            }
        }
    }

    override fun containing(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
    ): List<String> =
        runner.inTransaction {
            dsl
                .select(p.PERIOD_REF)
                .from(p)
                .where(p.TENANT_ID.eq(tenant.value))
                .and(p.ENTITY_REF.eq(entityRef))
                .and(p.PERIOD_START.le(date))
                .and(p.PERIOD_END.ge(date))
                .orderBy(p.PERIOD_REF)
                .limit(2)
                .fetch(p.PERIOD_REF)
                .map { checkNotNull(it) }
        }
}
