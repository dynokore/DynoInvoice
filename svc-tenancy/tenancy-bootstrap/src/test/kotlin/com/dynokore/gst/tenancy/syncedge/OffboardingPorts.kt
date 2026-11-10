package com.dynokore.gst.tenancy.syncedge

import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The documents-count stub and the export port of the offboarding tests (the real ones belong to later-wave services,
 * INV-Q-4900). It sits outside the packages the application scans, so only a test that lists it puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class OffboardingPorts {
    @Bean
    @Primary
    fun stubDocuments(tenants: TenantContext): UnsubmittedDocuments =
        UnsubmittedDocuments { _: OffboardingScope, _: String? ->
            calls.incrementAndGet()
            actors.add(tenants.currentActor())
            unsubmitted
        }

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

        /** How often the use case reached its documents port (the double); a refused caller leaves it at 0. */
        val calls = AtomicInteger()

        /** `tenants.currentActor()` at each call of the double. */
        val actors = CopyOnWriteArrayList<String?>()

        fun reset() {
            calls.set(0)
            actors.clear()
            unsubmitted = 0
            state = ExportState.READY
            contents = ExportContent.entries
        }
    }
}
