package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId

/**
 * One item to queue. Items of one [partitionKey] (the Doc UUID text for a document) in one lane are claimed
 * strictly in enqueue order; [consumer] names the only worker that may claim it (replay), or is `null`.
 */
data class QueueEntry(
    val partitionKey: String,
    val lane: Lane,
    val payloadJson: String,
    val consumer: String? = null,
) {
    init {
        require(partitionKey.isNotBlank()) { "partition key must not be blank" }
    }
}

/** An item a worker has claimed; [seq] identifies it to the other [WorkQueue] methods. */
data class ClaimedItem(
    val seq: Long,
    val tenant: TenantId,
    val partitionKey: String,
    val lane: Lane,
)

/**
 * The partition-ordered, lane-prioritised queue of a service's own schema (INV-REQ-540, INV-REQ-1072).
 * Enqueue and complete join the caller's tenant transaction (ARC-21); claiming is cross-tenant through the
 * sys-owned `claim_due` function on the runtime login (ARC 4.3 step 1a), with `FOR UPDATE SKIP LOCKED`.
 */
interface WorkQueue {
    /** Queues [entry] for the tenant bound in the caller's open transaction and returns its position. */
    fun enqueue(entry: QueueEntry): Long

    /**
     * Claims the next item: the highest-priority [Lane] that has a claimable item and spare rate limit, and in it
     * the oldest unclaimed item of a partition that has no earlier unfinished item in the same lane. Returns `null`
     * when nothing may be claimed now. A claim draws one permit from the item's own lane only.
     */
    fun claimNext(): ClaimedItem?

    /** The payload of [item], read in the caller's tenant transaction. */
    fun payloadOf(item: ClaimedItem): String

    /** Finishes [item] in the caller's tenant transaction; the next item of its partition becomes claimable. */
    fun complete(item: ClaimedItem)

    /** Gives [item] back after a failure so it is claimed again, in order, in the caller's tenant transaction. */
    fun release(item: ClaimedItem)
}
