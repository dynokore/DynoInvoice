package com.dynokore.gst.notification.bootstrap

import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import tools.jackson.databind.ObjectMapper

/** An item whose payload topic no consumer of this service owns. It is released and never delivered to a default. */
class UnroutableReplayItem(
    val topic: String?,
) : RuntimeException("no inbox consumer owns the replay topic $topic")

/**
 * [ReplayDelivery] for a service with several inbox consumers (INV-T-20914). It claims the next replay item of [queue],
 * reads the `topic` and `raw` of the payload in one short tenant transaction, and applies the envelope through the
 * consumer in [routes] that owns the topic, in the item's tenant transaction (same discipline as the library's
 * `JdbcReplayDelivery`). A topic no route owns, or an item whose handler throws, is released and the call throws.
 */
class TopicRoutingReplayDelivery(
    private val queue: WorkQueue,
    private val scope: TenantScope,
    private val routes: Map<String, JdbcInboxConsumer>,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ReplayDelivery {
    override fun runOnce(): ReplayOutcome? {
        val item = queue.claimNext() ?: return null
        try {
            val node = mapper.readTree(inTenant(item) { queue.payloadOf(item) })
            val topic = node.get("topic")?.takeIf { it.isString }?.asString()
            val inbox = routes[topic] ?: throw UnroutableReplayItem(topic)
            val raw = node.get("raw").asString()
            return inTenant(item) {
                val outcome = inbox.applyReplayed(raw, item.tenant)
                queue.complete(item)
                outcome
            }
        } catch (expected: Exception) {
            inTenant(item) { queue.release(item) }
            throw expected
        }
    }

    private fun <T> inTenant(
        item: ClaimedItem,
        block: () -> T,
    ): T = scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { block() } }
}
