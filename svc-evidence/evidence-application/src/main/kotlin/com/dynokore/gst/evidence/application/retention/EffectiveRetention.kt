package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.RetentionTier
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/**
 * The years a tier is retained for an entity on a date (INV-REQ-638 b1; INV-AC-2390, INV-AC-10452): the field of the
 * entity-scope VP-43 value in force, else the field of the tenant-scope value in force, else [defaultYears], the
 * catalogue default. The reader picks the most specific scope in force; an entity value is a complete value of the
 * three tiers (INV-REQ-1304), so a tier is never completed from the tenant value. The tenant is the one bound for
 * the request (ARC-21).
 */
class EffectiveRetention(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val defaultYears: Int,
    private val record: TierYearsReader,
) {
    fun years(
        entity: String,
        tier: RetentionTier,
        on: LocalDate,
    ): Int {
        val resolved = reader.resolve(RetentionPolicy.KEY, PolicyScope.entity(tenants.current(), entity), on)
        val selected = resolved.selected.single()
        if (selected.key != RetentionPolicy.LONGER) return defaultYears
        return record.years(requireNotNull(selected.valueJson) { "a LONGER value carries its record" }, tier.property)
    }
}
