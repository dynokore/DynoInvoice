package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/** Fires the job `accreditation-step-retry` through the [JobRuntime] on the cron of `gst.jobs.accreditation-step-retry.cron`. */
class AccreditationStepRetrySchedule(
    private val runtime: JobRuntime,
    private val cron: String,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addCronTask(CronTask(::fire, cron))
    }

    @Suppress("TooGenericExceptionCaught")
    fun fire() {
        try {
            runtime.fire(AccreditationStepRetryJob.NAME)
        } catch (e: Exception) {
            // the next tick retries; a failed run is already recorded by the job runtime
            log.warn("{} firing failed: {}", AccreditationStepRetryJob.NAME, e.javaClass.simpleName)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AccreditationStepRetrySchedule::class.java)
    }
}
