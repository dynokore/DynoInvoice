package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.export.FormatWorkPaperRenderer
import com.dynokore.gst.reconciliation.adapters.export.PdfWorkPaperRenderer
import com.dynokore.gst.reconciliation.adapters.export.XlsxWorkPaperRenderer
import com.dynokore.gst.reconciliation.adapters.http.AuthorityReplicaExportAuthority
import com.dynokore.gst.reconciliation.adapters.http.ExportAuthority
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunItems
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsSource
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The beans of the `exportWorkPaper` endpoint (INV-T-24486, INV-REQ-982): the scoped-permission authority over
 * `AuthorityReplica.decide`, the renderer that dispatches PDF and spreadsheet (INV-T-24543) and the
 * `ExportWorkPaper` use case.
 * No `RunItems` bean exists on main, so the use case takes `JooqRunItems(dsl)` directly, as `JooqCompletedRuns` does.
 */
@Configuration(proxyBeanMethods = false)
class ExportEndpointConfiguration {
    @Bean
    fun exportAuthority(
        replica: AuthorityReplica,
        tenants: TenantContext,
    ): ExportAuthority = AuthorityReplicaExportAuthority(replica, tenants)

    @Bean
    fun workPaperRenderer(): WorkPaperRenderer =
        FormatWorkPaperRenderer(XlsxWorkPaperRenderer(), PdfWorkPaperRenderer())

    @Bean
    @Suppress("LongParameterList")
    fun exportWorkPaper(
        getWorkPaper: GetWorkPaper,
        annotations: RunAnnotations,
        dsl: DSLContext,
        relaxedControls: RelaxedControlsSource,
        renderer: WorkPaperRenderer,
        clock: ObjectProvider<Clock>,
    ): ExportWorkPaper =
        ExportWorkPaper(
            getWorkPaper,
            annotations,
            JooqRunItems(dsl),
            relaxedControls,
            renderer,
            clock.getIfAvailable { Clock.systemUTC() },
        )
}
