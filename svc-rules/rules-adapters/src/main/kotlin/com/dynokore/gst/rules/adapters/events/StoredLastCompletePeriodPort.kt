package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.PeriodSpan
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import java.time.Clock

/**
 * The production [LastCompletePeriodPort] (INV-T-20226, INV-REQ-1303, INV-REQ-772): reads the period records that
 * tenancy-svc published and the read model stored. A period is complete when its `periodEnd` is strictly before the
 * clock date (owner ruling 2026-11-16, option A); the clock date is the Asia/Singapore date of the clock instant, never
 * the UTC date (INV-Q-2940, [BusinessDate]). The answer is the `periodRef` of the complete span with the greatest
 * `periodEnd`, or null when none is complete. An entity with no stored record is never guessed: it is refused.
 */
class StoredLastCompletePeriodPort(
    private val spans: (TenantId, String) -> List<PeriodSpan>?,
    private val clock: Clock,
) : LastCompletePeriodPort {
    override fun lastCompletePeriod(
        tenant: TenantId,
        entityRef: String,
    ): String? {
        val stored =
            try {
                spans(tenant, entityRef)
            } catch (e: DataAccessException) {
                throw PeriodCalendarUnavailableException(
                    "the period calendar cannot be read: ${e.javaClass.simpleName}",
                )
            } ?: throw PeriodCalendarUnavailableException("no period calendar record is stored for the entity")
        val today = BusinessDate.today(clock)
        return stored
            .filter { it.periodEnd.isBefore(today) }
            .maxByOrNull { it.periodEnd }
            ?.periodRef
    }
}
