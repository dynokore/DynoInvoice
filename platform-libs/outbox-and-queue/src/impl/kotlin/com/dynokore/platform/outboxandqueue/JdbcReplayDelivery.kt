package com.dynokore.platform.outboxandqueue

import tools.jackson.databind.ObjectMapper

/**
 * [ReplayDelivery] for the consumer that owns [inbox]. [queue] must be built with this consumer's name, so it
 * only ever claims the replay items queued for it. Claim, apply and completion run in the item's tenant
 * transaction: an item whose handler throws is released and delivered again, in order.
 */
class JdbcReplayDelivery(
    private val queue: WorkQueue,
    private val scope: TenantScope,
    private val inbox: JdbcInboxConsumer,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ReplayDelivery {
    override fun runOnce(): ReplayOutcome? {
        val item = queue.claimNext() ?: return null
        try {
            return scope.tenants.bind(item.tenant) {
                scope.transactions.inTransaction {
                    val raw = mapper.readTree(queue.payloadOf(item)).get("raw").asString()
                    val outcome = inbox.applyReplayed(raw, item.tenant)
                    queue.complete(item)
                    outcome
                }
            }
        } catch (failure: Exception) {
            scope.tenants.bind(item.tenant) { scope.transactions.inTransaction { queue.release(item) } }
            throw failure
        }
    }
}
