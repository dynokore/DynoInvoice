package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.persistence.SupportWindowRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.TrainRepositoryJooq
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.PackReleaseRecords
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The release gates: trains, the supported-version window and the corpus gate of signPack (INV-REQ-2657). */
@Configuration(proxyBeanMethods = false)
class ReleaseOperationsConfiguration {
    @Bean
    fun trainRepository(dsl: DSLContext): TrainRepository = TrainRepositoryJooq(dsl)

    @Bean
    fun supportWindowRepository(
        dsl: DSLContext,
        clock: Clock,
    ): SupportWindowRepository = SupportWindowRepositoryJooq(dsl, clock)

    @Bean
    fun publishTrain(
        trains: TrainRepository,
        roles: OperatorRoles,
        evidence: TrainEvidenceSource,
        accreditation: AccreditationTasks,
        clock: Clock,
    ) = PublishTrain(trains, roles, evidence, accreditation, clock)

    @Bean
    fun setSupportWindow(
        windows: SupportWindowRepository,
        roles: OperatorRoles,
    ) = SetSupportWindow(windows, roles)

    @Bean
    fun corpusGate(
        corpus: ConformanceCorpus,
        records: PackReleaseRecords,
    ) = CorpusGate(corpus, records)
}
