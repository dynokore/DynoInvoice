package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAnnotations
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAttestations
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The beans of the work-paper read and the variance annotation (INV-T-24380, INV-REQ-633): the append-only annotation
 * store and the attestation read as adapters of their ports, and the `getWorkPaper` and `annotateVariance` use cases.
 * The use cases open no transaction; the controller does.
 */
@Configuration(proxyBeanMethods = false)
class WorkPaperReadConfiguration {
    @Bean
    fun runAnnotations(dsl: DSLContext): RunAnnotations = JooqRunAnnotations(dsl)

    @Bean
    fun runAttestations(dsl: DSLContext): RunAttestations = JooqRunAttestations(dsl)

    @Bean
    fun getWorkPaper(
        reader: RunReader,
        annotations: RunAnnotations,
        attestations: RunAttestations,
    ): GetWorkPaper = GetWorkPaper(reader, annotations, attestations)

    @Bean
    fun annotateVariance(
        reader: RunReader,
        annotations: RunAnnotations,
        attestations: RunAttestations,
        clock: ObjectProvider<Clock>,
    ): AnnotateVariance =
        AnnotateVariance(reader, annotations, attestations, clock.getIfAvailable { Clock.systemUTC() })
}
