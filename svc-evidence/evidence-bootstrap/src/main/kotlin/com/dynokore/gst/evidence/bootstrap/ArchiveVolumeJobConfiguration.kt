package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ArchiveVolumeEntitySource
import com.dynokore.gst.evidence.adapters.persistence.JooqArchiveVolumeLedger
import com.dynokore.gst.evidence.adapters.persistence.JooqStoredContentBytes
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreBytes
import com.dynokore.gst.evidence.application.archive.ArchiveVolumePublisher
import com.dynokore.gst.evidence.application.archive.ArchiveVolumeSource
import com.dynokore.gst.evidence.application.archive.RetainedArchiveVolume
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRunContext
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The daily archive-volume job of INV-Q-9829 A: the values are those of INV-T-9909. */
object ArchiveVolumeJob {
    const val NAME = "archive-volume-measurement"

    val DEFINITION =
        JobDefinition(
            name = NAME,
            ownerService = "evidence-svc",
            entityScope = JobEntityScope.ENTITY,
            schedule = JobSchedule("0 30 1 * * *"),
            criticality = JobCriticality.STANDARD,
            tolerance = Duration.ofHours(6),
            lockAtMostFor = Duration.ofHours(2),
        )
}

/**
 * The body of `archive-volume-measurement` for one entity (INV-Q-12683 A): in one tenant transaction it measures the
 * retained volume and publishes it. A failed measurement or publication rolls the transaction back, so neither a
 * ledger row nor an outbox row remains (fail closed). The trace ids follow INV-Q-12736 A: a fresh correlation id
 * `job-run:<UUID>` per run, and a causation id that names the firing and not the attempt, so a retry keeps it.
 */
class ArchiveVolumeJobBody(
    private val service: String,
    private val scope: TenantScope,
    private val source: ArchiveVolumeSource,
    private val publisher: ArchiveVolumePublisher,
    private val newRunId: () -> UUID = UUID::randomUUID,
) : JobBody {
    override fun run(context: JobRunContext) {
        val tenant = context.target.tenant
        val entity = context.target.entity.value
        val actor = WorkloadPrincipal(service).job(context.job.name)
        val correlation = CorrelationId("job-run:${newRunId()}")
        val causation =
            CausationId(
                listOf(
                    context.job.name,
                    tenant.value.toString().lowercase(Locale.ROOT),
                    entity,
                    context.trigger.name,
                    DateTimeFormatter.ISO_INSTANT.format(context.scheduledFor),
                ).joinToString(":"),
            )
        scope.bind(tenant, actor) {
            scope.transactions.inTransaction {
                publisher.publish(tenant, entity, source.bytesRetained(tenant, entity), correlation, causation)
            }
        }
    }
}

/**
 * The daily measurement of the retained archive volume (INV-Q-9829 A): the sum of the five database content columns
 * and the objects under `period-digests/` (INV-Q-12681 A, INV-Q-12679 A), published per entity, and, when the service
 * has a [JobRuntime], the registration of the job `archive-volume-measurement`.
 */
@Configuration(proxyBeanMethods = false)
class ArchiveVolumeJobConfiguration {
    @Bean
    fun archiveVolumeSource(
        dsl: DSLContext,
        store: ObjectStore,
    ): ArchiveVolumeSource = RetainedArchiveVolume(JooqStoredContentBytes(dsl), ObjectStoreBytes(store))

    @Bean
    fun archiveVolumePublisher(
        dsl: DSLContext,
        outbox: OutboxWriter,
        clock: Clock,
    ): ArchiveVolumePublisher = ArchiveVolumePublisher(JooqArchiveVolumeLedger(dsl), outbox, clock)

    @Bean
    fun archiveVolumeJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        scope: TenantScope,
        dsl: DSLContext,
        source: ArchiveVolumeSource,
        publisher: ArchiveVolumePublisher,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable { registerArchiveVolumeJob(it, scope, dsl, source, publisher) }
    }

    companion object {
        /** Registers the job with exactly [ArchiveVolumeJob.DEFINITION]. */
        fun registerArchiveVolumeJob(
            runtime: JobRuntime,
            scope: TenantScope,
            dsl: DSLContext,
            source: ArchiveVolumeSource,
            publisher: ArchiveVolumePublisher,
        ) = runtime.register(
            ArchiveVolumeJob.DEFINITION,
            ArchiveVolumeEntitySource(scope, dsl),
            ArchiveVolumeJobBody(ArchiveVolumeJob.DEFINITION.ownerService, scope, source, publisher),
        )
    }
}
