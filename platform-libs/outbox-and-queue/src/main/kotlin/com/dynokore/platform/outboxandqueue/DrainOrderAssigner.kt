package com.dynokore.platform.outboxandqueue

/**
 * Sets the drain order of queued items (INV-REQ-554): tier 1 a document inside its own safety-margin window, tier 2
 * the backlog of the drained period, tier 3 routine traffic. Claiming orders the heads of partitions by tier, then
 * rank (null last), then enqueue position; it never lets a later item of a partition overtake an earlier one
 * (INV-REQ-540). A separate interface so that no [WorkQueue] implementor breaks.
 */
interface DrainOrderAssigner {
    /**
     * Sets [tier] and [rank] on the `PENDING` items of [partitionKey] of the tenant bound in the caller's open
     * transaction (no new transaction), and moves them to [lane] when it is given. Returns the number of items
     * changed; a `CLAIMED` item and any other tenant's item are left as they are.
     *
     * @throws IllegalArgumentException when [tier] is outside 1 to 3 or [rank] is negative, before any statement.
     */
    fun assignDrainOrder(
        partitionKey: String,
        tier: Int,
        rank: Int?,
        lane: Lane? = null,
    ): Int
}
