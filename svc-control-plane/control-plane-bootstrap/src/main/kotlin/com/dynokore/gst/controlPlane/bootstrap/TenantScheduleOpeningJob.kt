package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.schedule.OpenDueTenants
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration

/**
 * The scheduled job that opens tenants (INV-REQ-8100 behaviors 3 and 6): a PLATFORM-scoped job of control-plane-svc on
 * the service's [JobRuntime], run without a person under `platform.bindPlatform`. Each run calls
 * [OpenDueTenants.runOnce]; the closing at the offboarding time is INV-T-8180. The cron comes from
 * `gst.jobs.tenant-schedule-opening.cron` and is fired by [TenantScheduleOpeningSchedule].
 */
class TenantScheduleOpeningJob(
    runtime: JobRuntime,
    service: String,
    cron: String,
    tolerance: Duration,
    private val with: Collaborators,
) {
    /** What the job runs with. */
    class Collaborators(
        val step: OpenDueTenants,
        val platform: PlatformTenantBinding,
        val batchSize: Int,
    )

    val definition =
        JobDefinition(NAME, service, JobEntityScope.PLATFORM, JobSchedule(cron), JobCriticality.STANDARD, tolerance)

    private val actor = service

    init {
        runtime.register(definition, body = JobBody { runOnce() })
    }

    /** Runs one batch and returns how many `openTenant` calls it made. */
    fun runOnce(): Int = with.platform.bindPlatform(actor) { with.step.runOnce(with.batchSize) }

    companion object {
        const val NAME = "tenant-schedule-opening"
        const val CRON_PROPERTY = "gst.jobs.tenant-schedule-opening.cron"
        const val TOLERANCE_PROPERTY = "gst.jobs.tenant-schedule-opening.tolerance"
        const val BATCH_PROPERTY = "gst.tenant-schedule-opening.batch-size"
    }
}

/** Fires the job `tenant-schedule-opening` through the [JobRuntime] on the cron of its property. */
class TenantScheduleOpeningSchedule(
    private val runtime: JobRuntime,
    private val cron: String,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addCronTask(CronTask(::fire, cron))
    }

    @Suppress("TooGenericExceptionCaught")
    fun fire() {
        try {
            runtime.fire(TenantScheduleOpeningJob.NAME)
        } catch (e: Exception) {
            // the next tick retries; a failed run is already recorded by the job runtime
            log.warn("{} firing failed: {}", TenantScheduleOpeningJob.NAME, e.javaClass.simpleName)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TenantScheduleOpeningSchedule::class.java)
    }
}
