package com.dynokore.platform.serviceruntime.replica

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The time each role-grant tombstone was made, oldest first, and its eviction after [ttl]. With no [ttl] nothing is
 * noted and nothing is evicted (INV-Q-13768 A).
 */
internal class TombstoneLog(
    private val clock: Clock,
    private val ttl: Duration?,
    private val store: PartitionedStore<RoleGrantKey, RoleGrant>,
) {
    private val noted = LinkedHashMap<RoleGrantKey, Instant>()

    /** Notes the time of a tombstone this call made, and forgets the time of a key that holds a value again. */
    fun track(
        key: RoleGrantKey,
        entryBefore: Entry<RoleGrant>?,
    ) {
        if (ttl == null) return
        val entry = store.get(key)
        synchronized(noted) {
            when {
                entry?.value != null -> {
                    noted.remove(key)
                }

                entry != entryBefore -> {
                    noted.remove(key)
                    noted[key] = clock.instant()
                }
            }
        }
    }

    /** Forgets every noted tombstone (the store is cleared by the caller). */
    fun clear() = synchronized(noted) { noted.clear() }

    /**
     * Removes the tombstones older than the time-to-live, at most [EVICTIONS_PER_APPLY] per call, oldest first.
     * A held grant is never removed.
     */
    fun evictExpired() {
        if (ttl == null) return
        val now = clock.instant()
        synchronized(noted) {
            val due = noted.entries.asSequence().takeWhile { Duration.between(it.value, now) > ttl }
            due.take(EVICTIONS_PER_APPLY).toList().forEach { (key, _) ->
                store.evictTombstone(key)
                noted.remove(key)
            }
        }
    }

    private companion object {
        const val EVICTIONS_PER_APPLY = 100
    }
}
