package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lag
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Requests the authority replica's full sync from `access-svc` at start-up, without the operator call (owner ruling
 * INV-Q-9047 A; INV-REQ-1201). A replica is in memory and empty after every restart, so once the application is ready
 * and the service subscribes to [TOPIC] while [AuthorityReplica.lag] is [Lag.Unknown], the component asks for a
 * FULL_SYNC through the same start path as `rebuildConsumer` ([ConsumerRebuildRuntime.requestColdStartFullSync]):
 * the head move, `ReplayRequested` and `AuditEntryRecorded` commit in one transaction, with actor
 * `system:replica-cold-start` and the replay id as reason. A service that does not subscribe to [TOPIC] writes nothing.
 *
 * Ownership of the snapshot: the replay ids this process created are kept in memory. A request records its replay id
 * as own only when the outcome says `created`; the answer for a replay already pending that this process did not
 * create (repeat rule of INV-Q-3665) is recorded as foreign. [isOwnReplay] tells them apart. A snapshot built from a
 * replay that was partly delivered to an earlier process is never accepted, so once a foreign replay has completed
 * and this process owns none, [sync] requests again (INV-Q-11159 A): the component is its own re-trigger. After the call
 * on `ApplicationReadyEvent` it runs [sync] every [recheckInterval] on one virtual thread named [NAME], in the
 * background pool lane, and logs and survives every exception so that the next run still happens; it stops with the
 * context ([SmartLifecycle], the shape of `StalenessCheckSchedule`). A run with nothing due returns `null` after one
 * in-memory check; a run while a foreign replay is still pending writes nothing (repeat answer).
 */
class ReplicaColdStartSync(
    private val runtime: ConsumerRebuildRuntime,
    private val replica: AuthorityReplica,
    private val recheckInterval: Duration = DEFAULT_RECHECK_INTERVAL,
) : ApplicationListener<ApplicationReadyEvent>,
    SmartLifecycle {
    private val lock = Any()
    private val own = mutableSetOf<UUID>()
    private val foreign = mutableSetOf<UUID>()

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        runSafely()
        start()
    }

    /** Starts the re-check runs; called after the ready event, never by the lifecycle processor ([isAutoStartup]). */
    override fun start() {
        synchronized(lock) {
            if (scheduler != null) return
            scheduler =
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(NAME).factory()).also {
                    it.scheduleWithFixedDelay(
                        { PoolLanes.inLane(PoolLane.BACKGROUND) { runSafely() } },
                        recheckInterval.toMillis(),
                        recheckInterval.toMillis(),
                        TimeUnit.MILLISECONDS,
                    )
                }
        }
    }

    override fun stop() {
        synchronized(lock) {
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    override fun isRunning(): Boolean = scheduler != null

    /** Not started with the context: no request may be written before the application is ready. */
    override fun isAutoStartup(): Boolean = false

    @Suppress("TooGenericExceptionCaught")
    private fun runSafely() {
        try {
            sync()
        } catch (expected: Exception) {
            log.warn("replica cold-start sync failed; the next run tries again", expected)
        }
    }

    /**
     * Requests the full sync when it is due; returns the outcome, or `null` when nothing was asked (the lag is
     * measured and no foreign replay is waiting on a request of ours). Safe to call again: a pending replay of this
     * process is answered as a repeat and writes nothing.
     */
    fun sync(): RebuildOutcome? =
        synchronized(lock) {
            if (!due()) return null
            val id = UUID.randomUUID().toString()
            val outcome =
                runtime.requestColdStartFullSync(
                    TOPIC,
                    CorrelationId("replica-cold-start-$id"),
                    CausationId("replica-cold-start"),
                )
            if (outcome is RebuildOutcome.Done) {
                val replayId = outcome.state.replayId
                when {
                    outcome.created -> own += replayId
                    replayId !in own -> foreign += replayId
                }
            }
            outcome
        }

    /** True when [replayId] was created by this process (the snapshot built from it may be accepted). */
    fun isOwnReplay(replayId: UUID): Boolean = synchronized(lock) { replayId in own }

    private fun due() = replica.lag() is Lag.Unknown || (foreign.isNotEmpty() && own.isEmpty())

    companion object {
        const val TOPIC = "gst.platform.authority-changed.v1"
        const val NAME = "replica-cold-start-recheck"

        /** An Inference (INV-Q-11159 A): the caller may set another interval. */
        val DEFAULT_RECHECK_INTERVAL: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(ReplicaColdStartSync::class.java)
    }
}
