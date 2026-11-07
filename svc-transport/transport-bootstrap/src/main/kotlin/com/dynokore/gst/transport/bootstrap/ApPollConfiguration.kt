package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.JooqPollCursorStore
import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.NotificationPage
import com.dynokore.gst.transport.application.NotificationSource
import com.dynokore.gst.transport.application.PollAccessPoint
import com.dynokore.gst.transport.application.PollCursorStore
import com.dynokore.gst.transport.application.ReceiveApNotification
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One poll this instance made: the adapter, when, and what the run did. */
data class PollLogEntry(
    val adapter: AdapterId,
    val at: Instant,
    val processed: Int,
    val replays: Int,
)

/** The polls this instance made (INV-AC-9489 counts, per instance, the instances whose log records a poll). */
class ApPollLog {
    private val all = CopyOnWriteArrayList<PollLogEntry>()
    val entries: List<PollLogEntry> get() = all.toList()

    fun record(entry: PollLogEntry) {
        all += entry
    }
}

/**
 * Leader-elected polling of the AP notifications (INV-REQ-586, INV-Q-9684): one job `ap-poll-<adapterId>` per
 * adapter with notifications POLLING, on the platform [JobRuntime] (ShedLock gives the election), each run being one
 * [PollAccessPoint] run. An adapter in CALLBACK mode has no job and is never polled.
 */
@Configuration(proxyBeanMethods = false)
class ApPollConfiguration {
    @Bean
    fun pollCursorStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        platform: PlatformTenantBinding,
        clock: Clock,
    ): PollCursorStore = JooqPollCursorStore(dsl, runner, platform, WORKLOAD_PRINCIPAL, clock)

    @Bean
    fun apPollLog(): ApPollLog = ApPollLog()

    @Bean
    fun pollAccessPoint(
        adapters: AccessPointAdapters,
        cursors: PollCursorStore,
        receive: ReceiveApNotification,
    ): PollAccessPoint {
        val source =
            object : NotificationSource {
                override fun pageAfter(
                    adapter: AdapterId,
                    cursor: String?,
                ): NotificationPage =
                    checkNotNull(adapters.find(adapter) as? NotificationSource) {
                        "adapter ${adapter.value} cannot be polled"
                    }.pageAfter(adapter, cursor)
            }
        return PollAccessPoint(source, cursors, receive)
    }

    @Bean
    fun apPollJobs(
        runtime: ObjectProvider<JobRuntime>,
        properties: ApProperties,
        poll: PollAccessPoint,
        pollLog: ApPollLog,
        clock: Clock,
    ): ApPollJobs {
        val polling = properties.adapters.any { it.notifications == NotificationMode.POLLING }
        // a service with no POLLING adapter needs no job runtime; with one, a missing runtime fails the start
        return ApPollJobs(if (polling) runtime.getObject() else null, properties, poll, pollLog, clock)
    }

    private companion object {
        const val WORKLOAD_PRINCIPAL = "svc:transport-svc"
    }
}

/** Registers the poll jobs and fires them at the poll interval on every instance; the runtime elects the one run. */
class ApPollJobs(
    private val runtime: JobRuntime?,
    properties: ApProperties,
    private val poll: PollAccessPoint,
    private val pollLog: ApPollLog,
    private val clock: Clock,
) : SmartLifecycle {
    private val interval: Duration? = properties.pollInterval
    private val adapters: List<AdapterId> =
        properties.adapters.filter { it.notifications == NotificationMode.POLLING }.map { AdapterId(it.id) }

    /** The job names, in adapter order. */
    val jobNames: List<String> = adapters.map { jobName(it) }

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    init {
        adapters.forEach { adapter ->
            val every = checkNotNull(interval) { "transport.ap.poll-interval is required" }
            val definition =
                JobDefinition(
                    jobName(adapter),
                    OWNER,
                    JobEntityScope.PLATFORM,
                    JobSchedule(ApPollSchedule.cron(every)),
                    JobCriticality.STANDARD,
                    every.multipliedBy(TOLERANCE_TICKS),
                    // one run per tick is the job_run key's; a lease would block ticks shorter than itself
                    lockAtLeastFor = Duration.ZERO,
                )
            checkNotNull(runtime) { "polling needs the job runtime" }.register(definition) { run(adapter) }
        }
    }

    private fun run(adapter: AdapterId) {
        val report = poll.run(adapter)
        pollLog.record(PollLogEntry(adapter, clock.instant(), report.processed, report.replays))
        (report.refused + report.unowned).forEach { log.warn("ap poll {}: notification {} skipped", adapter.value, it) }
    }

    override fun start() {
        if (scheduler != null || adapters.isEmpty()) return
        val every = checkNotNull(interval).toMillis()
        checkNotNull(runtime).announce()
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ap-poll").factory()).also {
                it.scheduleWithFixedDelay({ jobNames.forEach(::fire) }, every, every, TimeUnit.MILLISECONDS)
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fire(job: String) {
        try {
            checkNotNull(runtime).fire(job)
        } catch (e: Exception) {
            // a failed run is recorded by the job runtime; the next tick polls again from the stored cursor
            log.warn("{} firing failed: {}", job, e.javaClass.simpleName)
        }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    companion object {
        private val log = LoggerFactory.getLogger(ApPollJobs::class.java)
        private const val OWNER = "transport-svc"
        private const val TOLERANCE_TICKS = 2L

        fun jobName(adapter: AdapterId): String = "ap-poll-${adapter.value.lowercase()}"
    }
}
