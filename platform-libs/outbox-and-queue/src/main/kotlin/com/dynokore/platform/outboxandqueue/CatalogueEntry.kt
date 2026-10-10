package com.dynokore.platform.outboxandqueue

/**
 * One event type of the catalogue (INV-NOTE-3 §19.8; platform-contracts `event-log/topics.yaml`):
 * the topic its relay inserts into and the services allowed to emit it.
 */
data class CatalogueEntry(
    val eventType: String,
    val topic: String,
    val producers: Set<String>,
) {
    /** Platform events are catalogued with the producer `every service` (INV-REQ-815 behaviour 4). */
    fun isProducedBy(service: String): Boolean = EVERY_SERVICE in producers || service in producers

    companion object {
        const val EVERY_SERVICE = "every service"
    }
}
