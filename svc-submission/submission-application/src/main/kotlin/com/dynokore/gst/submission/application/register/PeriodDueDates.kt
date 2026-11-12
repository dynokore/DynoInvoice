package com.dynokore.gst.submission.application.register

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The filing due date of a period as the PeriodDueDateChanged feed last set it (INV-REQ-570, INV-T-20514). */
interface PeriodDueDates {
    /** Stores [dueDate]; a row changes only for a [dueDateVersion] higher than the stored one (UNCHANGED otherwise). */
    fun upsert(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        dueDate: LocalDate,
        dueDateVersion: Long,
    ): UpsertResult

    /** The stored due date of the tenant's entity and period; null when there is no row. */
    fun find(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): LocalDate?
}
