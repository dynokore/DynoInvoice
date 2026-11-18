package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.events.EvidenceArtefactOutbox
import com.dynokore.gst.reconciliation.adapters.persistence.JooqSealedWorkPapers
import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.gst.reconciliation.application.workpaper.SealedWorkPapers
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * The adapters of the seal ports (INV-T-24187, INV-AC-24183, INV-Q-24164 A). The retention of a sealed object is
 * configuration the deployment supplies (12-factor): `reconciliation.work-paper.retention-days` has no default, so a
 * missing or non-positive value fails the context at start-up. No `SealWorkPaper` bean and no trigger are declared
 * here: they belong to the tasks that build the run read model.
 */
@Configuration(proxyBeanMethods = false)
class WorkPaperSealConfiguration {
    @Bean
    fun sealedWorkPapers(
        dsl: DSLContext,
        store: ObjectStore,
        clock: ObjectProvider<Clock>,
        environment: Environment,
    ): SealedWorkPapers =
        JooqSealedWorkPapers(
            dsl,
            store,
            clock.getIfAvailable { Clock.systemUTC() },
            environment.getRequiredProperty(RETENTION_PROPERTY, Long::class.java),
        )

    @Bean
    fun artefactRecordedPublisher(recorder: EvidenceArtefactRecorder): ArtefactRecordedPublisher =
        EvidenceArtefactOutbox(recorder)

    companion object {
        const val RETENTION_PROPERTY = "reconciliation.work-paper.retention-days"
    }
}
