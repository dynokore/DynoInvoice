package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.support.CronTrigger
import java.time.Clock

/**
 * The ports service-runtime's JobRuntime leaves to the service, and the scheduled firing of the verification job
 * (INV-Q-12992 A, INV-Q-9637 A). Who may command a job is not ruled yet: until it is, the authorizer fails closed, so
 * no run-now, pause or resume is permitted to anyone, and no role name is read (INV-Q-10027). The job and its
 * registration belong to ChainVerificationConfiguration.
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
     * Registers one cron task per definition the JobRuntime holds, once at startup (INV-AC-2126 asks for no
     * re-registration). The cron and the zone are read from the registered definition, never copied as text; the build
     * refuses a string attribute on @Scheduled (INV-Q-4690), so the tasks are registered through the configurer.
     */
    @Bean
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
}
