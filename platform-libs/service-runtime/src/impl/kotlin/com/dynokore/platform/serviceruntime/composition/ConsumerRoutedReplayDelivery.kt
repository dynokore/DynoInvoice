package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import tools.jackson.databind.ObjectMapper

/**
 * [ReplayDelivery] for every consumer of the service (INV-AC-24577, INV-T-24578): the [JdbcInboxConsumer] beans are
 * grouped by [JdbcInboxConsumer.subscription]'s consumer name, and each name has its own queue ([queueOf] builds it
 * with that consumer name), so an item queued for one consumer is claimed through that consumer only. [runOnce] asks
 * the consumers in the order of their names and returns the first non-null outcome.
 *
 * A claimed item is applied through the bean of its consumer whose topic holds the record type of the item's envelope,
 * read through the [catalogue] (a consumer with one bean and an item of its topic behaves as it always did); an item
 * whose type belongs to no bean of its consumer is dead-lettered and completed with [ReplayOutcome.REJECTED].
 *
 * Two rules (rulings of INV-T-24578):
 * - An item of a multi-bean consumer whose raw text is no valid envelope has no trustworthy topic: it is sorted to no
 *   bean and is dead-lettered once through [JdbcInboxConsumer.rejectReplayed] with the reason `not an envelope`,
 *   outcome [ReplayOutcome.REJECTED] (Enterprise Integration Patterns, Invalid Message Channel).
 * - Replay routing serves [JdbcInboxConsumer] beans only. The caller passes only those; other `InboxConsumer` beans
 *   (for example those of svc-submission) stay legal and add no start-up failure. An item whose topic no
 *   [JdbcInboxConsumer] bean of its consumer serves is dead-lettered with a reason that names its record type and says
 *   that replay serves JDBC consumer beans only; a consumer name served only by other bean types builds no route.
 */
class ConsumerRoutedReplayDelivery(
    consumers: Collection<JdbcInboxConsumer>,
    private val catalogue: EventCatalogue,
    private val scope: TenantScope,
    queueOf: (String) -> WorkQueue,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val otherBeans: Int = 0,
) : ReplayDelivery {
    private val routes: List<Route> =
        consumers
            .groupBy { it.subscription.consumer }
            .toSortedMap()
            .map { (name, beans) -> Route(queueOf(name), beans) }

    override fun runOnce(): ReplayOutcome? = routes.firstNotNullOfOrNull { it.runOnce() }

    /** One consumer name: its queue and its beans. */
    private inner class Route(
        private val queue: WorkQueue,
        private val beans: List<JdbcInboxConsumer>,
    ) {
        private val byTopic: Map<String, JdbcInboxConsumer> = beans.associateBy { it.subscription.topic }
        private val json = EnvelopeJson(mapper)

        /**
         * A consumer with one bean, in a context with no other [InboxConsumer] bean ([otherBeans] is 0), behaves as
         * before the routing: that bean serves every item of its queue.
         */
        private val single: ReplayDelivery? =
            if (beans.size == 1 && otherBeans == 0) JdbcReplayDelivery(queue, scope, beans.single(), mapper) else null

        fun runOnce(): ReplayOutcome? {
            single?.let { return it.runOnce() }
            val item = queue.claimNext()
            return if (item == null) null else routed(item)
        }

        private fun routed(item: ClaimedItem): ReplayOutcome? {
            val held = HeldQueue(queue, item)
            return try {
                val raw = rawOf(item)
                val type = typeOf(raw)
                if (type == null) {
                    reject(item, raw, NOT_AN_ENVELOPE)
                } else {
                    val bean = catalogue.find(type)?.topic?.let { byTopic[it] }
                    if (bean == null) {
                        val consumer = beans.first().subscription.consumer
                        val reason = "no consumer bean of $consumer for record type $type"
                        reject(item, raw, "$reason; replay serves JDBC consumer beans only")
                    } else {
                        JdbcReplayDelivery(held, scope, bean, mapper).runOnce()
                    }
                }
            } catch (expected: Exception) {
                if (!held.taken) release(item)
                throw expected
            }
        }

        private fun rawOf(item: ClaimedItem): String {
            val payload = inTenant(item) { queue.payloadOf(item) }
            return mapper.readTree(payload).get("raw").asString()
        }

        /** Dead-letters [raw] once through the first bean (it only records) with [reason] and completes [item]. */
        private fun reject(
            item: ClaimedItem,
            raw: String,
            reason: String,
        ): ReplayOutcome {
            beans.first().rejectReplayed(raw, reason)
            inTenant(item) { queue.complete(item) }
            return ReplayOutcome.REJECTED
        }

        /** The record type of [raw], or null when [raw] is no valid envelope. */
        private fun typeOf(raw: String): String? =
            try {
                json.read(raw).type
            } catch (invalid: ContractViolation) {
                null
            }

        private fun release(item: ClaimedItem) {
            inTenant(item) { queue.release(item) }
        }

        private fun <T> inTenant(
            item: ClaimedItem,
            block: () -> T,
        ): T = scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { block() } }
    }

    /** A queue whose next claim is the item already claimed from [delegate]; the rest is the delegate's. */
    private class HeldQueue(
        private val delegate: WorkQueue,
        private val held: ClaimedItem,
    ) : WorkQueue by delegate {
        var taken = false
            private set

        override fun claimNext(): ClaimedItem? {
            taken = true
            return held
        }
    }
}

private const val NOT_AN_ENVELOPE = "not an envelope"
