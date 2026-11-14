package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.StoredCompleteness
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The production [ReadinessInputSource] (INV-REQ-71, INV-REQ-792, INV-AC-22208): the stored start date of the entity and
 * the completeness derived from the stored rows of the entity and period, both under the current tenant only. An entity
 * without a stored start date answers `ReadinessInputs(null, Unavailable)`: the counter reads a null start date as no
 * filter, so the completeness must not pass (fail closed). No other service and no clock is called.
 */
class StoredReadinessInputSource(
    private val tenants: TenantContext,
    private val startDates: ObligationStartDates,
    private val rows: CompletenessRows,
) : ReadinessInputSource {
    override fun inputs(
        entityRef: String,
        periodRef: String,
    ): ReadinessInputs {
        val tenant = tenants.current()
        val startDate =
            startDates.find(tenant, entityRef)
                ?: return ReadinessInputs(null, CompletenessState.Unavailable)
        return ReadinessInputs(startDate, StoredCompleteness.derive(rows.rows(tenant, entityRef, periodRef)))
    }
}
