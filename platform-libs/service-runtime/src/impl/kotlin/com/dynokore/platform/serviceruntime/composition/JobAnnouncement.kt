package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener

/**
 * Calls [JobRuntime.announce] once when the application is ready (INV-T-20639, INV-AC-20638; INV-REQ-1226): emits
 * `JobRegistered` per registered job and refuses the start when the job-retention row is missing or differs. A context
 * with no [JobRuntime] does nothing. The exception of `announce()` is not caught, so it fails the start.
 */
class JobAnnouncement(
    private val jobRuntime: ObjectProvider<JobRuntime>,
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        jobRuntime.ifAvailable?.announce()
    }
}
