package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/** Fires the job `pack-stage` through the [JobRuntime] on the cron of the property `gst.jobs.pack-stage.cron`. */
class PackStageSchedule(
    private val runtime: JobRuntime,
    private val cron: String,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addCronTask(CronTask(::fire, cron))
    }

    @Suppress("TooGenericExceptionCaught")
    fun fire() {
        try {
            runtime.fire(PackStageJob.NAME)
        } catch (e: Exception) {
            // the next tick retries; a failed run is already recorded by the job runtime
            log.warn("{} firing failed: {}", PackStageJob.NAME, e.javaClass.simpleName)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PackStageSchedule::class.java)
    }
}
