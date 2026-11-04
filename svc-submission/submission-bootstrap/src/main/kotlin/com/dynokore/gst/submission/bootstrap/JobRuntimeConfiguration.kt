package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.WaitingSweeper
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.support.CronTrigger
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * The ports service-runtime's JobRuntime leaves to the service, the scheduled firing of the jobs and the registration
 * of the job `waiting-sweep` (INV-Q-15328 A, INV-REQ-1136). Who may command a job is not ruled yet: until it is, the
 * authorizer fails closed, so no run-now, pause or resume is permitted to anyone, and no role name is read
 * (INV-Q-10027).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class JobRuntimeConfiguration {
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

    /** No job of the service serves a due date yet, so every target answers null. */
    @Bean
    fun deadlineSource(): FixedDeadlineSource = FixedDeadlineSource()

    /**
     * Registers one cron task per definition the JobRuntime holds, once at startup. The cron and the zone are read from
     * the registered definition, never copied as text; the build refuses a string attribute on @Scheduled (INV-Q-4690),
     * so the tasks are registered through the configurer. The switch
     * `inv.submission.job-scheduling.enabled` (INV-Q-16793 A) is on when true or missing.
     */
    @Bean
    @ConditionalOnProperty(
        name = ["inv.submission.job-scheduling.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun jobScheduling(
        runtime: ObjectProvider<JobRuntime>,
        clock: Clock,
    ): SchedulingConfigurer =
        SchedulingConfigurer { registrar ->
            runtime.ifAvailable { jobs ->
                jobs.registered().forEach { definition ->
                    registrar.addCronTask(
                        CronTask(
                            { jobs.fire(definition.name, clock.instant()) },
                            CronTrigger(definition.schedule.cron, definition.schedule.zone),
                        ),
                    )
                }
            }
        }

    /** Registers [WAITING_SWEEP] in the service's [JobRuntime]; without a runtime nothing is registered. */
    @Bean
    fun waitingSweepJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        sweeper: WaitingSweeper,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable { registerWaitingSweep(it, sweeper) }
    }

    companion object {
        /**
         * The safety net of the wait (INV-REQ-571 behaviour 2): one firing a minute, [WaitingSweeper.INTERVAL]. The
         * criticality and the tolerance are inferred, not ruled: the sweep is not a deadline-bound despatch and the
         * service has no due-date source, and a tolerance is a small multiple of the interval (five minutes).
         */
        val WAITING_SWEEP =
            JobDefinition(
                name = "waiting-sweep",
                ownerService = "submission-svc",
                entityScope = JobEntityScope.PLATFORM,
                schedule = JobSchedule("0 * * * * *", ZoneOffset.UTC),
                criticality = JobCriticality.STANDARD,
                tolerance = Duration.ofMinutes(5),
            )

        private val LOG = System.getLogger(JobRuntimeConfiguration::class.java.name)

        /** Registers [WAITING_SWEEP]; its body calls [WaitingSweeper.sweep] once and lets an exception propagate. */
        fun registerWaitingSweep(
            runtime: JobRuntime,
            sweeper: WaitingSweeper,
        ) = runtime.register(WAITING_SWEEP, null) {
            val released = sweeper.sweep()
            LOG.log(System.Logger.Level.INFO, "waiting sweep released {0} version(s)", released)
        }
    }
}
