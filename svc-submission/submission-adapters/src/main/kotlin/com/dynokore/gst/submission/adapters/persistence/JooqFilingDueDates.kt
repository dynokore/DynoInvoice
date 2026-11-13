package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodDueDate
import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [FilingDueDates] over `submission.period_due_date` (INV-T-20626): exactly one row gives its date, none gives null,
 * more than one throws [AmbiguousDueDateException]. The tenant is in the WHERE clause as well as in row level security.
 */
class JooqFilingDueDates(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : FilingDueDates {
    private val p = PeriodDueDate.PERIOD_DUE_DATE

    override fun dueDateOf(
        entityRef: String,
        periodRef: String,
    ): LocalDate? {
        val dates =
            dsl
                .select(p.FILING_DUE_DATE)
                .from(p)
                .where(p.TENANT_ID.eq(tenants.current().value))
                .and(p.ENTITY_REF.eq(entityRef))
                .and(p.PERIOD_REF.eq(periodRef))
                .limit(2)
                .fetch(p.FILING_DUE_DATE)
        if (dates.size > 1) throw AmbiguousDueDateException("More than one due date exists for the period")
        return dates.firstOrNull()
    }
}
