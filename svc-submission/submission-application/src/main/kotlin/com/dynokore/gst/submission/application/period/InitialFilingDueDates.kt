package com.dynokore.gst.submission.application.period

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * Initial filing due date of a period (INV-REQ-570). Stores [dueDate] with version 0 only when the tenant has no row
 * for (entity, period); never changes an existing row; runs in the caller's transaction.
 */
fun interface InitialFilingDueDates {
    fun initialise(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        dueDate: LocalDate,
    )
}
