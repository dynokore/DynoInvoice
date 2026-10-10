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
    data class Measured(val duration: Duration) : Lag {
        init {
            require(!duration.isNegative) { "lag must not be negative, was $duration" }
        }
    }

    data object Unknown : Lag

    /** True when the lag is known and at most [bound] (INV-REQ-1201: "at most one second" is inclusive). */
    fun isWithin(bound: Duration): Boolean = this is Measured && duration <= bound
}

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
 * must reorder before it reaches the replica. Time comes from the injected [Clock]
 * (ARC-13).
 */
class ReplicaLagTracker(private val clock: Clock) {
    private val lock = Any()
    private var applied = NONE
    private var head = NONE
    private var behindSince: Instant? = null
    private var lastContact: Instant? = null
    private var disconnected = false
    private var synced = false

    fun onApplied(version: Long) = synchronized(lock) {
        applied = maxOf(applied, version)
        head = maxOf(head, applied)
        if (applied >= head) behindSince = null
        lastContact = clock.instant()
        disconnected = false
    }

    fun observeHead(version: Long) = synchronized(lock) {
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
    fun onSnapshotComplete(version: Long) = synchronized(lock) {
        applied = maxOf(applied, version)
        head = maxOf(head, applied)
        if (applied >= head) behindSince = null
        synced = true
        lastContact = clock.instant()
        disconnected = false
    }

    /** The feed has dropped. The lag of a caught-up replica now grows from the last contact. */
    fun onDisconnected() = synchronized(lock) {
        disconnected = true
    }

    /** The highest version applied so far, or -1 before the first. */
    fun appliedVersion(): Long = synchronized(lock) { applied }

    fun lag(): Lag = synchronized(lock) {
        val contact = lastContact
        if (!synced || contact == null) return Lag.Unknown
        val now = clock.instant()
        val sinceBehind = behindSince?.let { Duration.between(it, now) }
        val sinceContact = if (disconnected) Duration.between(contact, now) else Duration.ZERO
        val lag = listOfNotNull(sinceBehind, sinceContact).maxOrNull() ?: Duration.ZERO
        Lag.Measured(if (lag.isNegative) Duration.ZERO else lag)
    }

    companion object {
        private const val NONE = -1L
    }
}
