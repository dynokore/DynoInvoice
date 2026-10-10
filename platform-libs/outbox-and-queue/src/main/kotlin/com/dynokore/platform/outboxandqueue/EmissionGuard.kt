package com.dynokore.platform.outboxandqueue

/**
 * Rejects an event type not catalogued for the emitting service (INV-REQ-815 behaviour 2,
 * INV-AC-1869). Applied by [OutboxWriter] before any row is written.
 */
class EmissionGuard(
    private val catalogue: EventCatalogue,
    private val service: String,
) {
    /** Returns the catalogue entry for [eventType]; throws [EmissionNotPermitted] otherwise. */
    fun require(eventType: String): CatalogueEntry {
        val entry =
            catalogue.find(eventType)
                ?: throw EmissionNotPermitted(service, eventType, "type is not in the event catalogue")
        if (!entry.isProducedBy(service)) {
            throw EmissionNotPermitted(service, eventType, "catalogued for ${entry.producers.sorted()}")
        }
        return entry
    }
}
