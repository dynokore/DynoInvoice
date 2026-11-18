package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.application.returns.RunTrigger
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import tools.jackson.databind.json.JsonMapper

/**
 * [RunTrigger] over the partition-ordered work queue (INV-T-24367, INV-REQ-653): the enqueue joins the caller's tenant
 * transaction (ARC-21), so a rolled-back upload queues nothing. The partition key is `<entityRef>:<periodRef>`, so the
 * runs of one period are claimed in upload order. The lane is [Lane.LIVE], "routine live submission": a run follows a
 * live upload and is neither near a deadline, nor a reprocessing, backfill or replay. The worker that claims the entry
 * belongs to the run-store slice.
 */
class QueueRunTrigger(
    private val queue: WorkQueue,
) : RunTrigger {
    override fun enqueue(
        tenant: String,
        entityRef: String,
        periodRef: String,
        returnVersion: Int,
    ) {
        val payload =
            MAPPER.writeValueAsString(
                linkedMapOf("entityRef" to entityRef, "periodRef" to periodRef, "returnVersion" to returnVersion),
            )
        queue.enqueue(QueueEntry(partitionKey = "$entityRef:$periodRef", lane = Lane.LIVE, payloadJson = payload))
    }

    companion object {
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
