package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodDueDate
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [PeriodDueDates] over `submission.period_due_date` in one transaction of the bound tenant: `insert ... on conflict
 * do nothing` (INSERTED), else an `update` guarded by a lower stored event sequence (UPDATED, or UNCHANGED). No select
 * before the write, so concurrent writers cannot race the result; forced row level security applies. Values are
 * never logged.
 */
class JooqPeriodDueDateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : PeriodDueDates {
    private val p = PeriodDueDate.PERIOD_DUE_DATE

    override fun upsert(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        dueDate: LocalDate,
        sequence: Long,
    ): UpsertResult =
        runner.inTransaction {
            val inserted =
                dsl
                    .insertInto(p)
                    .set(p.TENANT_ID, tenant.value)
                    .set(p.ENTITY_REF, entityRef)
                    .set(p.PERIOD_REF, periodRef)
                    .set(p.FILING_DUE_DATE, dueDate)
                    .set(p.EVENT_SEQUENCE, sequence)
                    .onConflict(p.TENANT_ID, p.ENTITY_REF, p.PERIOD_REF)
                    .doNothing()
                    .execute()
            if (inserted == 1) {
                UpsertResult.INSERTED
            } else {
                val updated =
                    dsl
                        .update(p)
                        .set(p.FILING_DUE_DATE, dueDate)
                        .set(p.EVENT_SEQUENCE, sequence)
                        .where(p.TENANT_ID.eq(tenant.value))
                        .and(p.ENTITY_REF.eq(entityRef))
                        .and(p.PERIOD_REF.eq(periodRef))
                        .and(p.EVENT_SEQUENCE.lt(sequence))
                        .execute()
                if (updated == 1) UpsertResult.UPDATED else UpsertResult.UNCHANGED
            }
        }

    override fun find(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): LocalDate? =
        runner.inTransaction {
            dsl
                .select(p.FILING_DUE_DATE)
                .from(p)
                .where(p.TENANT_ID.eq(tenant.value))
                .and(p.ENTITY_REF.eq(entityRef))
                .and(p.PERIOD_REF.eq(periodRef))
                .fetchOne(p.FILING_DUE_DATE)
        }
}
