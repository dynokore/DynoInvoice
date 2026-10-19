package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The API-15 key purge as a platform job of the service (coordinator ruling INV-Q-3798): [NAME] is registered
 * in the service's [JobRuntime] as a PLATFORM-scoped job, so it is announced with `JobRegistered`, runs under
 * ShedLock leader election with one `job_run` per hourly tick, and its body is [IdempotencyStore.purge] on the
 * runtime login through the `<svc>_sys` purge function (INV-Q-4070). Every instance polls [JobRuntime.fire] every
 * [pollEvery]; the lock and the tick's `job_run` key
 * make one run per tick across the instances.
 */
class IdempotencyKeyPurgeJob(
    private val runtime: JobRuntime,
    service: String,
    store: IdempotencyStore,
    private val pollEvery: Duration = POLL_EVERY,
) : SmartLifecycle {
    val definition =
        JobDefinition(NAME, service, JobEntityScope.PLATFORM, JobSchedule(SCHEDULE), JobCriticality.STANDARD, TOLERANCE)

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    init {
        runtime.register(definition) { store.purge() }
    }

    override fun start() {
        if (scheduler != null) return
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(NAME).factory()).also {
                it.scheduleWithFixedDelay(::fire, pollEvery.toMillis(), pollEvery.toMillis(), TimeUnit.MILLISECONDS)
            }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    @Suppress("TooGenericExceptionCaught")
    private fun fire() {
        try {
            runtime.fire(NAME)
        } catch (e: Exception) {
            // the next poll retries; a failed run is already recorded by the job runtime
            log.warn("{} firing failed: {}", NAME, e.javaClass.simpleName)
        }
    }

    companion object {
        const val NAME = "idempotency-key-purge"

        /** Hourly, at the top of the hour (six-field cron, TPL-09). */
        const val SCHEDULE = "0 0 * * * *"
        val TOLERANCE: Duration = Duration.ofMinutes(15)
        val POLL_EVERY: Duration = Duration.ofMinutes(5)
        private val log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob::class.java)
    }
}
