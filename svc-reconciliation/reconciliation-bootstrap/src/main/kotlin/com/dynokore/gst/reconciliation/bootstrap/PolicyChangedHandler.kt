package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.productruntime.feed.PolicyFeed

/**
 * Feeds each `PolicyChanged` record of [PolicyReplicaConfiguration.POLICY_CHANGED_TOPIC] into the policy replica
 * (INV-REQ-982 item 3, option D). The platform reader of [PolicyFeed] decodes the record; this handler never reads
 * the payload and never builds a [LogRecord]: the replica orders values by the record's offset, so the record the
 * platform polled is the one that is applied. A [ContractViolation] of the reader propagates and the inbox
 * dead-letters the record.
 *
 * Fail closed: the one-argument [handle] has no position and applies nothing; a replayed dead letter
 * ([LogRecord.replayed]) has no position either, so it is rejected (dead-lettered again), never dropped.
 */
class PolicyChangedHandler(
    private val feed: PolicyFeed,
    private val topic: String = PolicyReplicaConfiguration.POLICY_CHANGED_TOPIC,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome =
        throw IllegalStateException(
            "PolicyChanged needs the log record of its position; the one-argument handle is refused",
        )

    override fun handle(
        event: Envelope,
        record: LogRecord,
    ): HandleOutcome {
        if (record.replayed) {
            throw ContractViolation(
                "a replayed PolicyChanged record has no position in the topic and cannot be ordered",
            )
        }
        feed.accept(topic, record)
        return HandleOutcome.APPLIED
    }
}
