package com.dynokore.platform.outboxandqueue

import java.util.UUID

/**
 * Told by a replay intake that a replay of [consumer] has completed (INV-Q-9047 A): the consumer applied the
 * ReplayCompleted marker(s) that complete [replayId], and the 'rebuild required' incident of each of [topics] was
 * closed. It is called once per completed replay, after the marker's transaction has committed. A listener that throws
 * does not undo the completion; the intake logs the exception and does not call the listener again for that replay.
 */
fun interface ReplayCompletionListener {
    fun onReplayCompleted(
        replayId: UUID,
        consumer: String,
        topics: List<String>,
    )
}
