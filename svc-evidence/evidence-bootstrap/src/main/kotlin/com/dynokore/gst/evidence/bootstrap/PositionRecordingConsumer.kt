package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult

/**
 * A record-topic consumer that writes its committed position after each poll (INV-REQ-3650 b2): [delegate] commits
 * every record in a transaction of its own, then [writer] records the committed offset. The write never throws, so a
 * failed one cannot fail or block the poll.
 */
class PositionRecordingConsumer(
    private val delegate: InboxConsumer,
    private val writer: RecordPositionWriter,
) : InboxConsumer {
    override fun pollOnce(): PollResult =
        try {
            delegate.pollOnce()
        } finally {
            // records already committed by a poll that then failed on a later record still advance the position
            writer.recordAfterCommit()
        }
}
