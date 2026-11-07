package com.dynokore.gst.masterdata.application.gapreport

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The tenants that hold a RUNNING long-command record started before a cut-off: tenant ids only (INV-Q-18051 A). */
fun interface StaleRecordTenantSource {
    fun tenants(before: Instant): List<TenantId>
}
