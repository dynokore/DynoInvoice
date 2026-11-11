package com.dynokore.gst.rules.application

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The last complete period of an entity, read from the period calendar (INV-REQ-1303, INV-REQ-772).
 *
 * "Complete" is as the owning service of the calendar (tenancy-svc) defines it; this service never derives it.
 * INV-T-4974 is the only caller. The production adapter is a later task.
 */
fun interface LastCompletePeriodPort {
    /**
     * The opaque period reference of the last complete period of [entityRef] of [tenant], or `null` when the entity has
     * no complete period yet.
     *
     * @throws PeriodCalendarUnavailableException when the calendar cannot be read; that is not the same answer as
     * `null`.
     */
    fun lastCompletePeriod(
        tenant: TenantId,
        entityRef: String,
    ): String?
}

/** The period calendar cannot be read; [reason] says why. */
class PeriodCalendarUnavailableException(
    val reason: String,
) : RuntimeException(reason)
