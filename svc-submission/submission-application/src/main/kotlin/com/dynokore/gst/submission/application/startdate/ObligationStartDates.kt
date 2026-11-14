package com.dynokore.gst.submission.application.startdate

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The obligation start date kept per tenant and entity from `EntityProfileChanged` (INV-REQ-335, INV-T-22201). */
interface ObligationStartDates {
    /**
     * Stores [startDate] for ([tenant], [entityRef]) when no row exists or [effectiveDate] is later than the stored
     * one. Returns true when the stored row changed; an older or equal [effectiveDate] changes nothing.
     */
    fun record(
        tenant: TenantId,
        entityRef: String,
        startDate: LocalDate,
        effectiveDate: LocalDate,
    ): Boolean

    /** The stored start date of the entity under [tenant] only, or null when none is stored. */
    fun find(
        tenant: TenantId,
        entityRef: String,
    ): LocalDate?
}
