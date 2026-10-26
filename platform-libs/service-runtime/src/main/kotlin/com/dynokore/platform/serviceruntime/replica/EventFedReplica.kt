package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import java.time.Clock
import java.time.Duration

/** One change from a feed, carrying the source's monotonic [version] (INV-REQ-1201 behaviour #1). */
sealed interface ReplicaChange<K, V> {
    val key: K
    val version: Long

    data class Upsert<K, V>(
        override val key: K,
        val value: V,
        override val version: Long,
    ) : ReplicaChange<K, V>

    data class Remove<K, V>(
        override val key: K,
        override val version: Long,
    ) : ReplicaChange<K, V>
}

/** Where a [EventFedReplica.get] answer came from. */
sealed interface Lookup<out V> {
    /** Served from the replica: the key is held and the replica is within its lag bound (or has none). */
    data class Local<V>(
        val value: V,
        val lag: Lag,
    ) : Lookup<V>

    /** A miss, or a replica beyond its lag bound, answered by the fallback call; the answer is now held. */
    data class Filled<V>(
        val value: V,
    ) : Lookup<V>

    /** The fallback was down, so the last state held is served, possibly stale (INV-REQ-1200 behaviour #3). */
    data class LastKnown<V>(
        val value: V,
        val lag: Lag,
    ) : Lookup<V>

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
 *
 * [partitionOf] (default none: one flat map) groups the entries so [heldIn] and [tombstoneIn] touch one
 * partition only (the authority replica's per-(tenant, principal) grants, INV-Q-9049 A); [heldWhere] and
 * [tombstoneIf] still scan every entry.
 *
 * [versionOf] reads the source version a fallback answer carries (the structure
 * replica's getEntityStructure fill, INV-Q-3324): a fill then keeps that version and
 * never overwrites a newer state held. Without it a fill keeps the held version.
 */
class EventFedReplica<K : Any, V : Any>(
    val name: String,
    private val clock: Clock,
    val lagBound: Duration? = null,
    private val fallback: ReplicaFallback<K, V>? = null,
    val tracker: ReplicaLagTracker = ReplicaLagTracker(clock),
    private val versionOf: ((V) -> Long)? = null,
    private val partitionOf: ((K) -> Any)? = null,
) {
    private val store = PartitionedStore<K, V>(partitionOf)

    /** Entries examined so far by [heldIn], [tombstoneIn], [heldWhere] and [tombstoneIf] (test-visible). */
    val visitCount: Long get() = store.visitCount

    init {
        require(lagBound == null || !lagBound.isNegative) { "lagBound must not be negative, was $lagBound" }
    }

    fun apply(change: ReplicaChange<K, V>) {
        store.compute(change.key) { old ->
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
        store.compute(key) { old ->
            if (old != null && version <= old.version) old else Entry(merge(old?.value), version)
        }
        tracker.onApplied(version)
    }

    /**
     * Turns every key matching [predicate] into a removal that keeps its held version (the authority replica's
     * stateless event): the value is dropped, but versions never go backwards, so an older redelivery stays out.
     */
    fun tombstoneIf(predicate: (K) -> Boolean) = store.tombstone(null, predicate)

    /** [tombstoneIf] for every key of [partition] only; other partitions are not visited. */
    fun tombstoneIn(partition: Any) = store.tombstone(partition) { true }

    /** Every value held (removals left out) whose key matches [predicate], with no fallback and no lag check. */
    fun heldWhere(predicate: (K) -> Boolean): Map<K, V> = store.held(null, predicate)

    /** Every value held in [partition] only (removals left out); other partitions are not visited. */
    fun heldIn(partition: Any): Map<K, V> = store.held(partition) { true }

    /** What is held for [key] with no fallback and no lag check; null for a miss or a held removal. */
    fun peek(key: K): V? = store.get(key)?.value

    fun lag(): Lag = tracker.lag()

    fun size(): Int = store.size()

    fun get(
        key: K,
        deadline: Deadline,
    ): Lookup<V> {
        val held = store.get(key)
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
    ): Lookup<V> =
        when (val answer = fallback?.fetch(key, deadline) ?: FallbackResult.Unavailable) {
            is FallbackResult.Found -> {
                store.fill(key, answer.value, versionOf)?.let { Lookup.Filled(it) }
                    ?: Lookup.Absent
            }

            FallbackResult.NotFound -> {
                Lookup.Absent
            }

            FallbackResult.Unavailable -> {
                held?.value?.let { Lookup.LastKnown(it, lag) } ?: Lookup.Unavailable
            }
        }
}
