package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId

/** What one poll run did; [refused] and [unowned] list the AP notification ids that were skipped (null id: absent). */
data class PollReport(
    val processed: Int,
    val replays: Int,
    val refused: List<String?>,
    val unowned: List<String?>,
)

/**
 * One poll run of a polling-only AP connection (INV-REQ-586, INV-Q-9684 A): reads the cursor, asks the source for the
 * page after it, passes each item through the callback path, then stores the next cursor. A source that throws, or
 * any failure before the store, leaves the cursor unchanged; the next run reads the same page and the ledger makes
 * each item count once.
 */
class PollAccessPoint(
    private val source: NotificationSource,
    private val cursors: PollCursorStore,
    private val receive: ReceiveApNotification,
) {
    fun run(adapter: AdapterId): PollReport {
        val page = source.pageAfter(adapter, cursors.read(adapter))
        var processed = 0
        var replays = 0
        val refused = mutableListOf<String?>()
        val unowned = mutableListOf<String?>()
        for (item in page.items) {
            when (val r = receive.receive(adapter, item)) {
                is ReceiveResult.Processed -> if (r.recorded) processed++ else replays++
                is ReceiveResult.Invalid -> refused += item.apNotificationId
                ReceiveResult.InboundNotSupported -> refused += item.apNotificationId
                ReceiveResult.OwnerNotFound -> unowned += item.apNotificationId
            }
        }
        cursors.write(adapter, page.nextCursor)
        return PollReport(processed, replays, refused, unowned)
    }
}
