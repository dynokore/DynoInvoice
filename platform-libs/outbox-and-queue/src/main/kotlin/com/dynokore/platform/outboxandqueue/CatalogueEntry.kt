package com.dynokore.platform.outboxandqueue

/**
 * One event type of the catalogue (INV-NOTE-3 §19.8; platform-contracts `event-log/topics.yaml`):
 * the topic its relay inserts into and the services allowed to emit it. [contentTopic] is the topic's
 * `content_topic` marker (its events carry invoice content, INV-REQ-1205 b4, INV-T-3386), or `null` when the file
 * carries no marker; the event-log purge refuses a topic without one rather than guess its period. [recordTopic] is
 * the topic's `record_topic` marker: its events are distinct append-only facts, deduped by event id only, with no
 * `docsequence` check and no held-sequence row (coordinator ruling INV-Q-3809, INV-REQ-1221 b4).
 */
data class CatalogueEntry
@JvmOverloads
constructor(
    val eventType: String,
    val topic: String,
    val producers: Set<String>,
    val contentTopic: Boolean? = null,
    val recordTopic: Boolean = false,
) {
    /** Platform events are catalogued with the producer `every service` (INV-REQ-815 behaviour 4). */
    fun isProducedBy(service: String): Boolean = EVERY_SERVICE in producers || service in producers

    companion object {
        const val EVERY_SERVICE = "every service"
    }
}
