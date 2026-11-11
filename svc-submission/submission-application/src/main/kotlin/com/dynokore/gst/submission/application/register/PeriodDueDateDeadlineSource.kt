package com.dynokore.gst.submission.application.register

import com.dynokore.platform.tenancykernel.TenantId

/**
 * [FilingDeadlineSource] over the stored period due dates. The recorded filing dates belong to the confirm-filing
 * task, so `filedOn` is empty. Null when the period has no row: the handler then retries (fail closed).
 */
class PeriodDueDateDeadlineSource(
    private val dueDates: PeriodDueDates,
) : FilingDeadlineSource {
    override fun inputs(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): FilingDeadlineInputs? =
        dueDates.find(tenant, entityRef, periodRef)?.let { FilingDeadlineInputs(it, emptyList()) }
}
