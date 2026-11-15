package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Runs one claimed build job of the platform [WorkQueue] (lane LIVE). The item's `partitionKey` is the Doc UUID text
 * and its payload is `{"docUuid":"<uuid>","version":<n>}`. [BuildPayload.run] and the completion of the item share one
 * tenant transaction, so a build that stages an event also finishes its job. Any failure rolls that transaction back,
 * so nothing is staged, then the item is released in a new transaction to be claimed again, and the failure
 * (`BuildNotReady` for a missing input or decision) propagates. A payload that does not name the partition's Doc UUID
 * and a version is refused the same way.
 */
class BuildPayloadJobHandler(
    private val queue: WorkQueue,
    private val scope: TenantScope,
    private val build: BuildPayload,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /** Claims and runs the next job; false when nothing was claimable. */
    fun runOnce(): Boolean {
        val item = queue.claimNext() ?: return false
        try {
            inTenant(item) {
                val (docUuid, version) = parse(item, queue.payloadOf(item))
                build.run(docUuid, version)
                queue.complete(item)
            }
        } catch (expected: Exception) {
            inTenant(item) { queue.release(item) }
            throw expected
        }
        return true
    }

    private fun parse(
        item: ClaimedItem,
        payload: String,
    ): Pair<UUID, Int> {
        val node = mapper.readTree(payload)
        val doc = UUID.fromString(node.path("docUuid").asString())
        check(doc.toString() == item.partitionKey) { "the build job payload names another document than its partition" }
        val version = node.path("version")
        check(version.isInt && version.asInt() >= 1) { "the build job payload has no version of 1 or more" }
        return doc to version.asInt()
    }

    private fun <T> inTenant(
        item: ClaimedItem,
        block: () -> T,
    ): T = scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { block() } }
}
