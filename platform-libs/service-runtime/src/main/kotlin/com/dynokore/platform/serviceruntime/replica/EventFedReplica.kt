package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One change from a feed, carrying the source's monotonic [version] (INV-REQ-1201 behaviour #1). */
sealed interface ReplicaChange<K, V> {
    val key: K
    val version: Long

    data class Upsert<K, V>(override val key: K, val value: V, override val version: Long) : ReplicaChange<K, V>

    data class Remove<K, V>(override val key: K, override val version: Long) : ReplicaChange<K, V>
}

/** Where a [EventFedReplica.get] answer came from. */
sealed interface Lookup<out V> {
    /** Served from the replica: the key is held and the replica is within its lag bound (or has none). */
    data class Local<V>(val value: V, val lag: Lag) : Lookup<V>

    /** A miss, or a replica beyond its lag bound, answered by the fallback call; the answer is now held. */
    data class Filled<V>(val value: V) : Lookup<V>

    /** The fallback was down, so the last state held is served, possibly stale (INV-REQ-1200 behaviour #3). */
    data class LastKnown<V>(val value: V, val lag: Lag) : Lookup<V>

    /** The key does not exist: a held removal, or the owner said so. */
    data object Absent : Lookup<Nothing>

    /** A miss with the fallback down: there is no state to serve. */
    data object Unavailable : Lookup<Nothing>
}

/**
 * A generic event-fed local replica (INV-REQ-1199, INV-REQ-1200; INV-ADR-284).
 *
 * State is changed only by [apply] from a feed, idempotently: a change at or below
 * a key's held version is ignored, so at-least-once delivery and redelivery are
 * harmless. Reads never block on the source.
 *
 * [get] serves in this order:
 *  1. held and (no [lagBound], or lag within it): served locally;
 *  2. held removal inside the same condition: [Lookup.Absent];
 *  3. otherwise (a miss, or lag beyond the bound) the [fallback] is called. Its
 *     answer is returned and held ([Lookup.Filled]);
 *  4. the fallback is down: the last state held is served ([Lookup.LastKnown]),
 *     or [Lookup.Unavailable] if there is none.
 *
 * A replica with no [lagBound] (the structure replica) therefore serves whatever
 * it holds, however old, and calls out only on a miss. A bounded one (the
 * authority replica) calls out as soon as it is stale.
 *
 * Held in memory, partitioned by key (which carries the tenant). A restart starts
 * empty: reads fill by fallback until the feed has caught up.
 */
class EventFedReplica<K : Any, V : Any>(
    val name: String,
    private val clock: Clock,
    val lagBound: Duration? = null,
    private val fallback: ReplicaFallback<K, V>? = null,
    val tracker: ReplicaLagTracker = ReplicaLagTracker(clock),
) {
    private data class Entry<V>(val value: V?, val version: Long)

    private val store = ConcurrentHashMap<K, Entry<V>>()

    init {
        require(lagBound == null || !lagBound.isNegative) { "lagBound must not be negative, was $lagBound" }
    }

    fun apply(change: ReplicaChange<K, V>) {
        store.compute(change.key) { _, old ->
            when {
                old != null && change.version <= old.version -> old
                change is ReplicaChange.Upsert -> Entry(change.value, change.version)
                else -> Entry(null, change.version)
            }
        }
        tracker.onApplied(change.version)
    }

    /** Applies a change computed from the state held, atomically (a partial change such as a profile attribute). */
    fun update(
        key: K,
        version: Long,
        merge: (V?) -> V,
    ) {
        store.compute(key) { _, old ->
            if (old != null && version <= old.version) old else Entry(merge(old?.value), version)
        }
        tracker.onApplied(version)
    }

    /** What is held for [key] with no fallback and no lag check; null for a miss or a held removal. */
    fun peek(key: K): V? = store[key]?.value

    fun lag(): Lag = tracker.lag()

    fun size(): Int = store.values.count { it.value != null }

    fun get(
        key: K,
        deadline: Deadline,
    ): Lookup<V> {
        val held = store[key]
        val lag = tracker.lag()
        val fresh = lagBound == null || lag.isWithin(lagBound)
        return if (held != null && fresh) {
            held.value?.let { Lookup.Local(it, lag) } ?: Lookup.Absent
        } else {
            callOwner(key, deadline, held, lag)
        }
    }

    private fun callOwner(
        key: K,
        deadline: Deadline,
        held: Entry<V>?,
        lag: Lag,
    ): Lookup<V> = when (val answer = fallback?.fetch(key, deadline) ?: FallbackResult.Unavailable) {
        is FallbackResult.Found -> {
            fill(key, answer.value)
            Lookup.Filled(answer.value)
        }

        FallbackResult.NotFound -> Lookup.Absent

        FallbackResult.Unavailable -> lastKnown(held, lag)
    }

    private fun lastKnown(
        held: Entry<V>?,
        lag: Lag,
    ): Lookup<V> = held?.value?.let { Lookup.LastKnown(it, lag) } ?: Lookup.Unavailable

    /** A fill carries no source version, so it keeps the held one: a later event still wins. */
    private fun fill(
        key: K,
        value: V,
    ) {
        store.compute(key) { _, old -> Entry(value, old?.version ?: NO_VERSION) }
    }

    private companion object {
        const val NO_VERSION = -1L
    }
}
