package com.dynokore.platform.outboxandqueue

import tools.jackson.databind.ObjectMapper

/**
 * [ReplayDelivery] for the consumer that owns [inbox]. [queue] must be built with this consumer's name, so it
 * only ever claims the replay items queued for it. One call holds at most one connection of its DataSource at any
 * instant: the payload is read in one short tenant transaction that closes before anything else. A valid envelope
 * is applied and completed in one tenant transaction; an invalid one (not an envelope, or another tenant's) is
 * dead-lettered on the claim connection outside any transaction, and only then completed in a second short
 * transaction, so a crash between the two leaves the item to be delivered again (at-least-once, one more dead
 * letter, as before). An item whose handler throws is released and delivered again, in order.
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
            val payload = inTenant(item) { queue.payloadOf(item) }
            val raw = mapper.readTree(payload).get("raw").asString()
            return when (val check = inbox.checkReplayed(raw, item.tenant)) {
                is ReplayCheck.Invalid -> {
                    inbox.deadLetterReplayed(raw, check.reason)
                    inTenant(item) { queue.complete(item) }
                    ReplayOutcome.REJECTED
                }

                is ReplayCheck.Valid -> {
                    inTenant(item) {
                        val outcome = inbox.applyChecked(check.envelope)
                        queue.complete(item)
                        outcome
                    }
                }
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
