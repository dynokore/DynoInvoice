package com.dynokore.platform.outboxandqueue

/** The event catalogue as the outbox sees it: which service emits which type, into which topic. */
class EventCatalogue(entries: Collection<CatalogueEntry>) {
    private val byType: Map<String, CatalogueEntry> = entries.associateBy { it.eventType }

    init {
        require(byType.size == entries.size) { "event types must be unique in the catalogue" }
    }

    /** The entry for [eventType], or `null` when the catalogue does not know it. */
    fun find(eventType: String): CatalogueEntry? = byType[eventType]

    /** Every catalogued entry. */
    fun entries(): Collection<CatalogueEntry> = byType.values
}
