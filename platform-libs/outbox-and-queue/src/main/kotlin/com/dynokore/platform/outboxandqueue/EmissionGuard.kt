package com.dynokore.platform.outboxandqueue

/**
 * Rejects an event type not catalogued for the emitting service (INV-REQ-815 behaviour 2,
 * INV-AC-1869). Applied by [OutboxWriter] before any row is written.
 */
class EmissionGuard(
    private val catalogue: EventCatalogue,
    private val service: String,
) {
    /**
     * Returns the catalogue entry for [eventType]; throws [EmissionNotPermitted] otherwise. A
     * [ReplayProtocol.REPLAY_REQUESTED] goes to the service's own request topic (INV-Q-3599), so only a service
     * that has one may write it.
     */
    fun require(eventType: String): CatalogueEntry {
        val refusal =
            when {
                eventType == ReplayProtocol.REPLAY_REQUESTED ->
                    catalogue.requestTopicOf(service)?.let { own ->
                        return CatalogueEntry(eventType, own.topic, setOf(service), contentTopic = false)
                    } ?: "the service has no request topic"

                else -> {
                    val entry = catalogue.find(eventType)
                    when {
                        entry == null -> "type is not in the event catalogue"
                        !entry.isProducedBy(service) -> "catalogued for ${entry.producers.sorted()}"
                        else -> return entry
                    }
                }
            }
        throw EmissionNotPermitted(service, eventType, refusal)
    }

    /**
     * Returns the replay topic [topic] when this service may insert [eventType] into it: the service is the pair's
     * producer (or the pair's producer is 'every service') and the topic carries the type (INV-Q-3601, INV-Q-3625).
     */
    fun requireReplay(
        topic: String,
        eventType: String,
    ): ReplayTopic {
        val replay = catalogue.replayTopicNamed(topic)
        val refusal =
            when {
                replay == null -> "$topic is not a replay topic"
                !replay.isProducedBy(service) -> "$topic is produced by ${replay.producer}"
                eventType !in replay.eventTypes -> "$topic does not carry it"
                else -> return replay
            }
        throw EmissionNotPermitted(service, eventType, refusal)
    }
}
