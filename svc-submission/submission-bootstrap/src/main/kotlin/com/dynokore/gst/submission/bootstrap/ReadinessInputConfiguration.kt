package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.readiness.ReadinessInputSource
import com.dynokore.gst.submission.application.readiness.StoredReadinessInputSource
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The production readiness input source over the stored start date and completeness rows (INV-AC-22208). */
@Configuration(proxyBeanMethods = false)
class ReadinessInputConfiguration {
    @Bean
    fun readinessInputSource(
        tenants: TenantContext,
        startDates: ObligationStartDates,
        rows: CompletenessRows,
    ): ReadinessInputSource = StoredReadinessInputSource(tenants, startDates, rows)
}
