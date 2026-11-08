package com.dynokore.gst.tenancy.syncedge

import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The documents-count stub and the export port of the offboarding tests (the real ones belong to later-wave services,
 * INV-Q-4900). It sits outside the packages the application scans, so only a test that lists it puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class OffboardingPorts {
    @Bean
    @Primary
    fun stubDocuments(): UnsubmittedDocuments = UnsubmittedDocuments { _: OffboardingScope, _: String? -> unsubmitted }

    @Bean
    @Primary
    fun stubExport(): TenantExport =
        TenantExport { order: ExportOrder -> ExportManifest("EXP-${order.offboardingRef}", state, contents) }

    companion object {
        @Volatile
        var unsubmitted: Int = 0

        @Volatile
        var state: ExportState = ExportState.READY

        @Volatile
        var contents: List<ExportContent> = ExportContent.entries

        fun reset() {
            unsubmitted = 0
            state = ExportState.READY
            contents = ExportContent.entries
        }
    }
}
