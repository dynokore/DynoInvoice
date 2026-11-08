package com.dynokore.platform.serviceruntime.replica

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How far a replica trails its source (INV-REQ-1201 behaviour #1: "each service
 * measures its own replica lag"). [Unknown] is a replica that has never heard
 * from its feed (a cold start): it is never within any bound, so a bounded
 * replica falls back at once and an unbounded one serves what it has.
 */
sealed interface Lag {
    data class Measured(
        val duration: Duration,
    ) : Lag {
        init {
            require(!duration.isNegative) { "lag must not be negative, was $duration" }
        }
    }

    data object Unknown : Lag

    /** True when the lag is known and at most [bound] (INV-REQ-1201: "at most one second" is inclusive). */
    fun isWithin(bound: Duration): Boolean = this is Measured && duration <= bound
}

/** Counts the ordering anomalies of the authority feed; the impl half records them as meters (INV-T-9262). */
fun interface ReplicaOrderCounter {
    fun increment(name: String)

    companion object {
        /** An AuthorityChanged (or head) whose incarnation is below the highest seen: refused. */
        const val LOWER_INCARNATION = "authority_replica_lower_incarnation_total"

        /** A head observed below the head held within the same incarnation: ignored for the lag. */
        const val HEAD_REGRESSED = "authority_replica_head_regressed_total"
        val NONE = ReplicaOrderCounter { }
    }
}

/** How an incarnation compares with the highest seen (INV-T-9262). */
enum class EpochOrder { SAME, LOWER, HIGHER }

/**
 * Measures one replica's lag from its feed (INV-REQ-1201 behaviour #1).
 *
 * The feed reports three things: an applied change ([onApplied], carrying the
 * source's monotonic version), the source's current head version ([observeHead],
 * a heartbeat, so a quiet source is not mistaken for a stalled one), and the
 * connection state ([onDisconnected]). From them:
 *  - behind: the head is ahead of what was applied; lag is the time since that
 *    head was first seen unapplied;
 *  - caught up and the feed alive: lag is zero;
 *  - caught up but the feed gone: lag is the time since the last contact, because
 *    anything published since is unseen. The replica falls back rather than
 *    trusting a silence it cannot tell from an outage;
 *  - not yet synced, or never in contact: [Lag.Unknown].
 *
 * Initial-sync gate: a replica starts empty after every restart, and a feed may resume
 * mid-log (a persisted consumer offset), so one applied event proves nothing about the
 * state held. Lag stays [Lag.Unknown], and a bounded replica keeps using its fallback,
 * until the feed reports a full sync or snapshot marker ([onSnapshotComplete]). *
 * Versions are ordered per feed: a feed that delivers out of order per partition
 * must reorder before it reaches the replica. A change or head is ordered by the pair (incarnation, version),
 * the incarnation first; an absent incarnation is [DEFAULT_INCARNATION] (INV-T-9262, INV-Q-9243 B). Time comes from the injected [Clock]
 * (ARC-13).
 */
class ReplicaLagTracker(
    private val clock: Clock,
    private val counter: ReplicaOrderCounter = ReplicaOrderCounter.NONE,
) {
    private val lock = Any()
    private var applied = NONE
    private var head = NONE
    private var behindSince: Instant? = null
    private var lastContact: Instant? = null
    private var disconnected = false
    private var synced = false
    private var silenceBound: Duration? = null
    private var incarnation = NO_INCARNATION

    /** The highest incarnation seen, or 0 before the first. */
    fun incarnation(): Long = synchronized(lock) { incarnation }

    /**
     * Orders [value] (null is [DEFAULT_INCARNATION]) against the highest seen. [EpochOrder.LOWER] changes nothing.
     * [EpochOrder.HIGHER] after one was seen forgets the lag state (applied, head, contact, snapshot), so the lag is
     * [Lag.Unknown] until a new snapshot, and keeps the new value; the caller then clears what it holds. The first
     * value ever seen is [EpochOrder.SAME]: there is nothing held to clear.
     */
    fun admitIncarnation(value: Long?): EpochOrder =
        synchronized(lock) {
            val epoch = value ?: DEFAULT_INCARNATION
            when {
                incarnation == NO_INCARNATION -> {
                    incarnation = epoch
                    EpochOrder.SAME
                }

                epoch < incarnation -> {
                    EpochOrder.LOWER
                }

                epoch == incarnation -> {
                    EpochOrder.SAME
                }

                else -> {
                    incarnation = epoch
                    applied = NONE
                    head = NONE
                    behindSince = null
                    lastContact = null
                    disconnected = false
                    synced = false
                    EpochOrder.HIGHER
                }
            }
        }

    fun onApplied(version: Long) =
        synchronized(lock) {
            applied = maxOf(applied, version)
            head = maxOf(head, applied)
            if (applied >= head) behindSince = null
            lastContact = clock.instant()
            disconnected = false
        }

    fun observeHead(version: Long) =
        synchronized(lock) {
            if (version < head) {
                counter.increment(ReplicaOrderCounter.HEAD_REGRESSED)
                return@synchronized
            }
            val now = clock.instant()
            if (version > head) head = version
            if (head > applied && behindSince == null) behindSince = now
            if (head <= applied) behindSince = null
            lastContact = now
            disconnected = false
        }

    /**
     * The feed has delivered everything up to [version] as a full sync or snapshot: the replica now
     * holds the source's state as of then, so lag can be measured. Called once per start.
     */
    fun onSnapshotComplete(version: Long) =
        synchronized(lock) {
            applied = maxOf(applied, version)
            head = maxOf(head, applied)
            if (applied >= head) behindSince = null
            synced = true
            lastContact = clock.instant()
            disconnected = false
        }

    /** The feed has dropped. The lag of a caught-up replica now grows from the last contact. */
    fun onDisconnected() =
        synchronized(lock) {
            disconnected = true
        }

    /**
     * The feed sends a heartbeat at least every [silenceBound] / 2, so silence longer than [silenceBound]
     * is a lost feed, counted like [onDisconnected]: lag then grows from the last contact. Called once by
     * a replica's feed; until it is called, silence changes nothing (INV-REQ-1201, INV-Q-9047 A).
     */
    fun expectHeartbeats(silenceBound: Duration) =
        synchronized(lock) {
            require(
                !silenceBound.isNegative && !silenceBound.isZero,
            ) { "silence bound must be positive, was $silenceBound" }
            check(this.silenceBound == null) { "expectHeartbeats is called once" }
            this.silenceBound = silenceBound
        }

    /** The highest version applied so far, or -1 before the first. */
    fun appliedVersion(): Long = synchronized(lock) { applied }

    fun lag(): Lag =
        synchronized(lock) {
            val contact = lastContact
            if (!synced || contact == null) return Lag.Unknown
            val now = clock.instant()
            val sinceBehind = behindSince?.let { Duration.between(it, now) }
            val silent = silenceBound?.let { Duration.between(contact, now) > it } ?: false
            val sinceContact = if (disconnected || silent) Duration.between(contact, now) else Duration.ZERO
            val lag = listOfNotNull(sinceBehind, sinceContact).maxOrNull() ?: Duration.ZERO
            Lag.Measured(if (lag.isNegative) Duration.ZERO else lag)
        }

    companion object {
        private const val NONE = -1L
        private const val NO_INCARNATION = 0L

        /** The incarnation of an event that carries none (INV-T-9262). */
        const val DEFAULT_INCARNATION = 1L
    }
}
