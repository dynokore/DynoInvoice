package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationStartDate
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * [ObligationStartDates] over `submission.obligation_start_date` in one transaction of the bound tenant: one
 * `insert ... on conflict do update ... where stored effective date < new effective date` statement, so an equal or
 * older effective date changes nothing and concurrent writers cannot race the result. Forced row level security
 * applies. Values are never logged.
 */
class JooqObligationStartDateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ObligationStartDates {
    private val o = ObligationStartDate.OBLIGATION_START_DATE

    override fun record(
        tenant: TenantId,
        entityRef: String,
        startDate: LocalDate,
        effectiveDate: LocalDate,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(o)
                .set(o.TENANT_ID, tenant.value)
                .set(o.ENTITY_REF, entityRef)
                .set(o.START_DATE, startDate)
                .set(o.EFFECTIVE_DATE, effectiveDate)
                .onConflict(o.TENANT_ID, o.ENTITY_REF)
                .doUpdate()
                .set(o.START_DATE, DSL.excluded(o.START_DATE))
                .set(o.EFFECTIVE_DATE, DSL.excluded(o.EFFECTIVE_DATE))
                .where(o.EFFECTIVE_DATE.lt(DSL.excluded(o.EFFECTIVE_DATE)))
                .execute() == 1
        }

    override fun find(
        tenant: TenantId,
        entityRef: String,
    ): LocalDate? =
        runner.inTransaction {
            dsl
                .select(o.START_DATE)
                .from(o)
                .where(o.TENANT_ID.eq(tenant.value))
                .and(o.ENTITY_REF.eq(entityRef))
                .fetchOne(o.START_DATE)
        }
}
