package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.persistence.PackRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.PlanRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.QualificationRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.persistence.SupportWindowRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.TrainRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.Controlplane
import com.dynokore.gst.controlPlane.application.commercial.ManagePlan
import com.dynokore.gst.controlPlane.application.commercial.PlanRepository
import com.dynokore.gst.controlPlane.application.commercial.QualificationRepository
import com.dynokore.gst.controlPlane.application.commercial.RecordQualification
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.GetPack
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.pack.StagingEvidenceReader
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.PackReleaseRecords
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import org.jooq.DSLContext
import org.jooq.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** What signPack reads and writes for the corpus gate: the golden corpus and the release records. */
class CorpusGate(
    val corpus: ConformanceCorpus,
    val records: PackReleaseRecords,
)

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03). */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** The generated jOOQ schema, which service-runtime classifies at start-up (TableClasses.verify, INV-Q-4815). */
    @Bean
    fun controlplaneSchema(): Schema = Controlplane.CONTROLPLANE

    @Bean
    fun packRepository(
        dsl: DSLContext,
        clock: Clock,
    ): PackRepository = PackRepositoryJooq(dsl, clock)

    @Bean
    fun stagingEvidenceReader(dsl: DSLContext): StagingEvidenceReader = StagingEvidenceReaderJooq(dsl)

    @Bean
    fun authorRegulatoryPack(
        packs: PackRepository,
        roles: OperatorRoles,
    ) = AuthorRegulatoryPack(packs, roles)

    @Bean
    fun signPack(
        packs: PackRepository,
        roles: OperatorRoles,
        keys: PackSigningKeys,
        gate: CorpusGate,
        clock: Clock,
    ) = SignPack(packs, roles, keys, gate.corpus, gate.records, clock)

    @Bean
    fun getPack(packs: PackRepository) = GetPack(packs)

    @Bean
    fun planRepository(
        dsl: DSLContext,
        clock: Clock,
    ): PlanRepository = PlanRepositoryJooq(dsl, clock)

    @Bean
    fun qualificationRepository(
        dsl: DSLContext,
        clock: Clock,
    ): QualificationRepository = QualificationRepositoryJooq(dsl, clock)

    @Bean
    fun managePlan(
        plans: PlanRepository,
        roles: OperatorRoles,
    ) = ManagePlan(plans, roles)

    @Bean
    fun recordQualification(
        qualifications: QualificationRepository,
        roles: OperatorRoles,
    ) = RecordQualification(qualifications, roles)
}
