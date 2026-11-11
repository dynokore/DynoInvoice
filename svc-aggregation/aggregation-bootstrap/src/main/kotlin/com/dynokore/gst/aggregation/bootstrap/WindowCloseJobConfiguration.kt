package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.events.WindowChangedOutbox
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowChangePublisher
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The scheduled closing run (INV-REQ-413 item 2, INV-Q-14448 A). One ENTITY-scoped job of service-runtime's
 * [JobRuntime] is registered; its body calls [CloseWindows] for the entity and the business date of the tick. Every
 * instance fires the tick from [WindowCloseSchedule]; ShedLock over the `shedlock` table of `V7__job_runtime.sql`
 * elects the leader and the `job_run` key makes one run per tick and entity. The service has no scheduler table of its
 * own. Until INV-T-16565 the [PeriodBoundarySource] knows no span, so every window is held and nothing closes.
 */
@Configuration(proxyBeanMethods = false)
class WindowCloseJobConfiguration {
    /** Who may run, pause or resume the job by command is not decided: nobody may; this reads no role. */
    @Bean
    fun jobCommandAuthorizer(): JobCommandAuthorizer =
        object : JobCommandAuthorizer {
            override fun mayCommandEntity(
                actor: String,
                tenant: TenantId,
                entity: EntityRef,
                command: JobCommand,
            ): Boolean = false

            override fun isPlatformOperator(
                actor: String,
                command: JobCommand,
            ): Boolean = false
        }

    /** No due date is served yet: the safety margin and the periods belong to the period source (INV-T-16565). */
    @Bean
    fun deadlineSource(): DeadlineSource = FixedDeadlineSource()

    @Bean
    fun windowChangePublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
    ): WindowChangePublisher = WindowChangedOutbox(outbox, tenants)

    @Bean
    fun openWindowEntities(
        scope: TenantScope,
        dsl: DSLContext,
    ): JobEntitySource = OpenWindowEntitySource(scope, dsl)

    @Bean
    fun windowCloseJob(
        jobs: JobRuntime,
        entities: JobEntitySource,
        scope: TenantScope,
        windows: WindowRepository,
        periods: PeriodBoundarySource,
        shortCodes: ShortCodes,
        aggregates: AggregateRepository,
        publisher: WindowChangePublisher,
    ): WindowCloseJob =
        WindowCloseJob(jobs, entities) { context ->
            val tenant = context.target.tenant
            val entity = context.target.entity.value
            val date = WindowCloseJob.businessDate(context.scheduledFor)
            val actor = WorkloadPrincipal(WindowCloseJob.SERVICE).job(context.job.name)
            // one transaction: the aggregates, the part rows and the outbox rows of the close commit together
            scope.bind(tenant, actor) {
                scope.transactions.inTransaction {
                    CloseWindows(
                        OneEntity(windows, entity),
                        periods,
                        IntakeAggregate(shortCodes, aggregates),
                        publisher,
                    ).execute(date)
                }
            }
        }
}

/** The windows of one entity only: the job runs once per entity, so a run never closes another entity's windows. */
private class OneEntity(
    private val delegate: WindowRepository,
    private val entityRef: String,
) : WindowRepository by delegate {
    override fun openWindowsEndingOnOrBefore(date: LocalDate): List<OpenWindow> =
        delegate.openWindowsEndingOnOrBefore(date).filter { it.entityRef == entityRef }
}

/** Registers the job and announces it once at start-up (INV-REQ-1226 b6); [fire] is one scheduled firing. */
class WindowCloseJob(
    private val jobs: JobRuntime,
    private val entities: JobEntitySource,
    val body: JobBody,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        jobs.register(DEFINITION, entities, body)
        jobs.announce()
    }

    fun fire() {
        jobs.fire(NAME)
    }

    companion object {
        const val NAME = "aggregation-window-close"
        const val SERVICE = "aggregation-svc"
        const val CRON = "0 5 0 * * *"
        const val ZONE = "Asia/Singapore"
        private const val TOLERANCE_HOURS = 6L

        val DEFINITION =
            JobDefinition(
                name = NAME,
                ownerService = SERVICE,
                entityScope = JobEntityScope.ENTITY,
                schedule = JobSchedule(CRON, ZoneId.of(ZONE)),
                criticality = JobCriticality.DEADLINE_CRITICAL,
                tolerance = Duration.ofHours(TOLERANCE_HOURS),
            )

        /** The tick fires just after midnight (Singapore): its business date is the day that has just ended. */
        fun businessDate(scheduledFor: java.time.Instant): LocalDate =
            scheduledFor.atZone(ZoneId.of(ZONE)).toLocalDate().minusDays(1)
    }
}

/** The `@Scheduled` method that calls [JobRuntime.fire] on every instance; the lock makes one of them run. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class WindowCloseSchedule(
    private val job: WindowCloseJob,
) {
    @Scheduled(cron = WindowCloseJob.CRON, zone = WindowCloseJob.ZONE)
    fun fire() = job.fire()
}

/**
 * The (tenant, entity) pairs that hold an open window: the key columns of the SECURITY DEFINER function
 * `aggregation.open_window_entities()` and nothing else, read as the platform tenant. The reserved tenant-wide entity
 * cannot be a [JobTarget.Entity], so a pair that names it is left out.
 */
class OpenWindowEntitySource(
    private val scope: TenantScope,
    private val dsl: DSLContext,
) : JobEntitySource {
    private val mapper = JsonMapper.builder().build()

    override fun entities(job: JobDefinition): List<JobTarget.Entity> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                dsl
                    .select(PAIR)
                    .fetch()
                    .map { mapper.readTree(it.value1().data()) }
                    .map {
                        EntityRef(it["entity_ref"].asString()) to TenantId(UUID.fromString(it["tenant_id"].asString()))
                    }.filterNot { (entity, _) -> entity.isTenantWide }
                    .map { (entity, tenant) -> JobTarget.Entity(tenant, entity) }
            }
        }

    private companion object {
        val PAIR =
            DSL.function(
                DSL.name("to_jsonb"),
                SQLDataType.JSONB,
                DSL.function(DSL.name("aggregation", "open_window_entities"), SQLDataType.OTHER),
            )
    }
}
