package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.gapreport.SweepStaleRecords
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
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.support.CronTrigger
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * The ports service-runtime's JobRuntime leaves to the service, the scheduled firing of the jobs and the registration
 * of the job `long-command-sweep` (INV-Q-18028 A, INV-Q-17291). Who may command a job is not ruled: the authorizer
 * fails closed, so no run-now, pause or resume is permitted to anyone, and no role name is read (INV-Q-10027).
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

    /** No job of the service serves a due date, so every target answers null. */
    @Bean
    fun deadlineSource(): FixedDeadlineSource = FixedDeadlineSource()

    /**
     * Registers one cron task per definition the JobRuntime holds, once at startup; cron and zone are read from the
     * registered definition. The switch `inv.masterdata.job-scheduling.enabled` is on when true or missing
     * (INV-Q-16793 A).
     */
    @Bean
    @ConditionalOnProperty(
        name = ["inv.masterdata.job-scheduling.enabled"],
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

    /**
     * Registers [LONG_COMMAND_SWEEP] in the service's [JobRuntime]. The cron is the key
     * `masterdata.long-command.sweep-cron`, a six-field cron with no default in code: a start without it, or with a
     * blank value, fails (12-factor: configuration in the environment).
     */
    @Bean
    fun longCommandSweepJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        sweep: SweepStaleRecords,
        environment: Environment,
    ): SmartInitializingSingleton {
        val cron = environment.getRequiredProperty(SWEEP_CRON_KEY)
        require(cron.isNotBlank()) { "$SWEEP_CRON_KEY must not be blank" }
        return SmartInitializingSingleton { runtime.ifAvailable { registerSweep(it, cron, sweep) } }
    }

    companion object {
        const val SWEEP_CRON_KEY = "masterdata.long-command.sweep-cron"

        /** The tolerance is inferred, not ruled: five minutes, a small multiple of the configured schedule. */
        private val TOLERANCE: Duration = Duration.ofMinutes(5)

        private val LOG = System.getLogger(JobRuntimeConfiguration::class.java.name)

        fun definition(cron: String) =
            JobDefinition(
                name = "long-command-sweep",
                ownerService = "masterdata-svc",
                entityScope = JobEntityScope.PLATFORM,
                schedule = JobSchedule(cron, ZoneOffset.UTC),
                criticality = JobCriticality.STANDARD,
                tolerance = TOLERANCE,
            )

        /** Registers the sweep; its body calls [SweepStaleRecords.run] once and lets an exception propagate. */
        fun registerSweep(
            runtime: JobRuntime,
            cron: String,
            sweep: SweepStaleRecords,
        ) = runtime.register(definition(cron), null) {
            LOG.log(System.Logger.Level.INFO, "long-command sweep finalised {0} record(s)", sweep.run())
        }
    }
}
