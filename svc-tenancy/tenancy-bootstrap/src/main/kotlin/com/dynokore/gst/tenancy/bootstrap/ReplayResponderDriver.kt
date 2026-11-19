package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.ReplayAnswer

/**
 * The loop body of the replay responder (INV-AC-25010): `EventLoops` drives every [InboxConsumer] bean, and one poll is
 * one run of the responder over the request topics. The result's `applied` is the number of replays answered, so the
 * loop polls again while a run answered something. A failure of [run] is rethrown: the loop's own backoff applies.
 */
class ReplayResponderDriver(
    private val run: () -> List<ReplayAnswer>,
) : InboxConsumer {
    override fun pollOnce(): PollResult = PollResult(run().size, 0, 0, 0, 0)
}
