package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.application.integrity.ChainVerificationJob
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
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Clock
import java.time.Duration
import java.time.Instant

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

    @Bean
    fun chainVerificationSchedule(
        runtime: ObjectProvider<JobRuntime>,
        clock: Clock,
    ): ChainVerificationSchedule = ChainVerificationSchedule(runtime, clock)
}

/**
 * Fires `evidence-chain-verification` at its declared schedule. The cron and the zone are read from
 * [ChainVerificationJob.DEFINITION], not copied as text: the build refuses a string attribute on @Scheduled (SpEL
 * templates included, INV-Q-4690), so the method wakes once a minute and [fire] runs the job only when a tick of the
 * definition's cron, in the definition's zone, lies in the minute that ends at the clock. A clock off every tick makes
 * no run.
 */
class ChainVerificationSchedule(
    private val runtime: ObjectProvider<JobRuntime>,
    private val clock: Clock,
) {
    @Scheduled(fixedRate = POLL_MILLIS)
    fun fire() {
        val now = clock.instant()
        if (onTick(now)) runtime.ifAvailable { it.fire(ChainVerificationJob.NAME, now) }
    }

    /** True when a tick of the definition's cron lies in the minute that ends at [now], in the definition's zone. */
    private fun onTick(now: Instant): Boolean {
        val schedule = ChainVerificationJob.DEFINITION.schedule
        val tick = CronExpression.parse(schedule.cron).next(now.minus(WINDOW).atZone(schedule.zone))
        return tick != null && !tick.toInstant().isAfter(now)
    }

    private companion object {
        const val POLL_MILLIS = 60_000L
        val WINDOW: Duration = Duration.ofMillis(POLL_MILLIS)
    }
}
