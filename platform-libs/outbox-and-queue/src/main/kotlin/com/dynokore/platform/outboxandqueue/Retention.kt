package com.dynokore.platform.outboxandqueue

import java.time.Duration

/**
 * How long this installation keeps what the outbox and inbox hold (INV-Q-2954 A2, INV-Q-3091, INV-ADR-2772).
 * Both periods are installation settings with a default of 30 days and a floor of 7 days, enforced here:
 * a shorter value is refused, so no configuration can purge sooner.
 *
 * - [contentWindow]: published outbox rows (whose `data` is content) are deleted once `published_at` is older
 *   than this. An unpublished row is never deleted.
 * - triaged dead letters (replayed or discarded; `raw` is content) are deleted once `resolved_at` is older than
 *   [contentWindow] too (INV-T-2997). An open dead letter is never deleted, so no event is lost silently.
 * - [eventLogRetention]: how long the event log keeps the events of a topic not marked as a content topic; a content
 *   topic keeps [contentWindow] ([EventLogPurge], INV-Q-3249). An inbox row can only matter while its event could
 *   still be redelivered, so it is deleted once `received_at` is older than this plus [INBOX_MARGIN]; the inbox has
 *   no topic column, so this one value serves every topic.
 */
data class Retention(
    val contentWindow: Duration = DEFAULT,
    val eventLogRetention: Duration = DEFAULT,
) {
    init {
        require(contentWindow >= FLOOR) { "content window must be at least $FLOOR, was $contentWindow" }
        require(eventLogRetention >= FLOOR) { "event-log retention must be at least $FLOOR, was $eventLogRetention" }
    }

    /** The age past which an inbox row is deleted. */
    val inboxRetention: Duration get() = eventLogRetention + INBOX_MARGIN

    companion object {
        val DEFAULT: Duration = Duration.ofDays(30)
        val FLOOR: Duration = Duration.ofDays(7)
        val INBOX_MARGIN: Duration = Duration.ofDays(1)
    }
}

/** Rows deleted by one [RetentionPurge.purge] run. */
data class PurgeResult(
    val outboxRows: Long,
    val inboxRows: Long,
    val deadLetters: Long = 0,
)

/** Deletes expired rows of one service schema's outbox, inbox and triaged dead letters, across tenants (INV-T-2998). */
interface RetentionPurge {
    /** One run; safe to repeat and to run from several workers. Deletes in batches until nothing expired is left. */
    fun purge(): PurgeResult
}
