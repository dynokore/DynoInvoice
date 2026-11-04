package com.dynokore.gst.submission.application.compliance

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * Source of the tenants of the daily compliance evaluation.
 *
 * Read outside any tenant transaction; returns tenant ids only.
 */
fun interface ComplianceTenantSource {
    fun tenants(): List<TenantId>
}

/**
 * Per-tenant emission ledger of the daily compliance evaluation.
 */
interface AlertEmissionLedger {
    /**
     * Runs in the caller's open tenant transaction.
     *
     * Returns true when it wrote the row for (tenant, entityRef, periodRef) or moved the stored day
     * forward because [measuredOn] is strictly later than the stored day, and false when [measuredOn]
     * equals or precedes the stored day.
     */
    fun claim(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        measuredOn: LocalDate,
    ): Boolean
}
