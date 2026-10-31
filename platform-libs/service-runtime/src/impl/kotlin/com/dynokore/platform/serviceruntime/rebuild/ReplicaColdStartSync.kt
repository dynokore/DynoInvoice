package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.StructureReplica
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
 *
 * The structure replica (INV-REQ-1199, INV-AC-9089): when [requireStructureSnapshot] (setting [REQUIRE_SNAPSHOT_SETTING],
 * default `false`) is true and [structure] is not [StructureReplica.isSynced], [syncStructure] requests one FULL_SYNC for
 * the pair (access-svc, `tenancy-svc`) addressed to `tenancy-svc` through the same start function (INV-Q-13786 A: one
 * per producer, not one per topic; it starts with the first of [structureTopics] and, as every FULL_SYNC, moves every
 * topic of `tenancy-svc` the service reads), with the same ownership, foreign-replay and repeat rules, tracked apart
 * from the authority request. With the setting false nothing is written for it, and
 * a request is never addressed to any producer but [TENANCY].
 */
class ReplicaColdStartSync(
    private val runtime: ConsumerRebuildRuntime,
    private val replica: AuthorityReplica,
    private val recheckInterval: Duration = DEFAULT_RECHECK_INTERVAL,
    private val structure: StructureReplica? = null,
    private val structureTopics: List<String> = emptyList(),
    private val requireStructureSnapshot: Boolean = false,
) : ApplicationListener<ApplicationReadyEvent>,
    SmartLifecycle {
    private val lock = Any()
    private val authorityReplays = Replays()
    private val structureReplays = Replays()

    private class Replays {
        val own = mutableSetOf<UUID>()
        val foreign = mutableSetOf<UUID>()

        fun record(outcome: RebuildOutcome) {
            if (outcome !is RebuildOutcome.Done) return
            val replayId = outcome.state.replayId
            when {
                outcome.created -> own += replayId
                replayId !in own -> foreign += replayId
            }
        }

        fun waitingOnOwn() = foreign.isNotEmpty() && own.isEmpty()
    }

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
        try {
            syncStructure()
        } catch (expected: Exception) {
            log.warn("structure replica cold-start sync failed; the next run tries again", expected)
        }
    }

    /**
     * Requests the full sync when it is due; returns the outcome, or `null` when nothing was asked (the lag is
     * measured and no foreign replay is waiting on a request of ours). Safe to call again: a pending replay of this
     * process is answered as a repeat and writes nothing.
     */
    fun sync(): RebuildOutcome? =
        synchronized(lock) {
            if (!(replica.lag() is Lag.Unknown || authorityReplays.waitingOnOwn())) return@synchronized null
            request(TOPIC, ACCESS, authorityReplays)
        }

    /**
     * Requests the structure replica's full sync from `tenancy-svc`: one FULL_SYNC for the pair (access-svc,
     * `tenancy-svc`), started with the first of [structureTopics] (INV-Q-13786 A). It is due when the setting is true
     * and the replica is not synced, or a foreign replay was found and this process owns none. Returns the outcome in
     * a list; empty when nothing is due. When any listed topic is not in the catalogue, is not subscribed, or lacks
     * [TENANCY] among its producers, the answer is a refusal and nothing is written.
     */
    fun syncStructure(): List<RebuildOutcome> =
        synchronized(lock) {
            if (!requireStructureSnapshot || structure == null) return@synchronized emptyList()
            val topics = structureTopics.distinct()
            if (topics.isEmpty() || (structure.isSynced() && !structureReplays.waitingOnOwn())) {
                return@synchronized emptyList()
            }
            if (!topics.all { runtime.acceptsColdStartTopic(it, TENANCY) }) {
                return@synchronized listOf(RebuildOutcome.Refused(RebuildErrorCode.TOPIC_NOT_CONSUMED))
            }
            listOf(request(topics.first(), TENANCY, structureReplays))
        }

    private fun request(
        topic: String,
        producer: String,
        replays: Replays,
    ): RebuildOutcome {
        val id = UUID.randomUUID().toString()
        val outcome =
            runtime.requestColdStartFullSync(
                topic,
                CorrelationId("replica-cold-start-$id"),
                CausationId("replica-cold-start"),
                producer,
            )
        replays.record(outcome)
        return outcome
    }

    /** True when [replayId] was created by this process (the snapshot built from it may be accepted). */
    fun isOwnReplay(replayId: UUID): Boolean =
        synchronized(lock) { replayId in authorityReplays.own || replayId in structureReplays.own }

    companion object {
        const val TOPIC = "gst.platform.authority-changed.v1"
        const val ACCESS = "access-svc"
        const val TENANCY = "tenancy-svc"
        const val REQUIRE_SNAPSHOT_SETTING = "serviceruntime.replica.structure.require-snapshot"
        const val NAME = "replica-cold-start-recheck"

        /** An Inference (INV-Q-11159 A): the caller may set another interval. */
        val DEFAULT_RECHECK_INTERVAL: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(ReplicaColdStartSync::class.java)
    }
}
