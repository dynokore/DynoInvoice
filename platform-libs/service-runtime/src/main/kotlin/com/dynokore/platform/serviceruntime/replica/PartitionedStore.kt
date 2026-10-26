package com.dynokore.platform.serviceruntime.replica

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One held key: the value (null for a removal) and the source version it was held at. */
internal data class Entry<V>(
    val value: V?,
    val version: Long,
)

/**
 * The map behind [EventFedReplica]: one map per partition; without [partitionOf] every key sits in the one
 * flat partition. A scan with a null partition reads every entry; with a partition it reads only that
 * partition's entries. [visitCount] counts the entries examined by the scans.
 */
internal class PartitionedStore<K : Any, V : Any>(
    private val partitionOf: ((K) -> Any)?,
) {
    private val partitions = ConcurrentHashMap<Any, ConcurrentHashMap<K, Entry<V>>>()
    private val visits = AtomicLong()

    val visitCount: Long get() = visits.get()

    private fun partitionKey(key: K): Any = partitionOf?.invoke(key) ?: FLAT

    fun get(key: K): Entry<V>? = partitions[partitionKey(key)]?.get(key)

    /** Atomically replaces the entry of [key] by what [change] returns for the entry held; returns the result. */
    fun compute(
        key: K,
        change: (Entry<V>?) -> Entry<V>,
    ): Entry<V> =
        checkNotNull(
            partitions
                .computeIfAbsent(partitionKey(key)) { ConcurrentHashMap() }
                .compute(key) { _, old -> change(old) },
        )

    fun size(): Int = partitions.values.sumOf { map -> map.values.count { it.value != null } }

    fun held(
        partition: Any?,
        predicate: (K) -> Boolean,
    ): Map<K, V> =
        buildMap {
            scope(partition).forEach { map ->
                map.forEach { (key, entry) ->
                    visits.incrementAndGet()
                    if (predicate(key)) entry.value?.let { put(key, it) }
                }
            }
        }

    /** Turns every matching key into a removal that keeps its held version. */
    fun tombstone(
        partition: Any?,
        predicate: (K) -> Boolean,
    ) {
        scope(partition).forEach { map ->
            map.keys
                .filter {
                    visits.incrementAndGet()
                    predicate(it)
                }.forEach { key -> map.computeIfPresent(key) { _, old -> Entry(null, old.version) } }
        }
    }

    /**
     * Holds a fallback answer and returns what is then held (null: a newer removal). With [versionOf] the
     * answer keeps its own version and loses to a newer state held (an event applied while the call was
     * out); without it the answer carries no source version, so it keeps the held one and a later event
     * still wins.
     */
    fun fill(
        key: K,
        value: V,
        versionOf: ((V) -> Long)?,
    ): V? =
        compute(key) { old ->
            when {
                versionOf == null -> Entry(value, old?.version ?: NO_VERSION)
                old != null && versionOf(value) <= old.version -> old
                else -> Entry(value, versionOf(value))
            }
        }.value

    /** The one partition's map, or every map when [partition] is null (a whole-store scan). */
    private fun scope(partition: Any?): Collection<ConcurrentHashMap<K, Entry<V>>> =
        if (partition == null) partitions.values else listOfNotNull(partitions[partition])

    private companion object {
        const val NO_VERSION = -1L
        val FLAT = Any()
    }
}
