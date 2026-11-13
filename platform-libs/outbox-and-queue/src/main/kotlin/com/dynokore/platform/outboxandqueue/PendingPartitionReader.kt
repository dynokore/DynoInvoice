package com.dynokore.platform.outboxandqueue

/**
 * Reads which partitions of the platform work queue still have unclaimed items (the drain of INV-REQ-554 needs the
 * partitions it may still order). A separate interface so that no [WorkQueue] implementor breaks (additive, like
 * [DrainOrderAssigner]).
 */
interface PendingPartitionReader {
    /**
     * The distinct partition keys of the `PENDING` items (every lane, every consumer) of the tenant bound in the
     * caller's open transaction (no new transaction), in ascending byte order of the key text (`COLLATE "C"`,
     * whatever the database collation). Reads no payload and no other column. A partition whose items are all
     * `CLAIMED` is not listed; the result is an empty list, never null, when nothing is pending.
     */
    fun pendingPartitionKeys(): List<String>
}
