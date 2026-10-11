package com.dynokore.platform.outboxandqueue

/**
 * The event catalogue as the outbox sees it: which service emits which type, into which topic; and, from
 * INV-T-3606, the replay topics (one per (producer, subscriber) pair) and the request topics (one per consumer) of
 * the replay protocol (INV-REQ-1205 b4).
 */
class EventCatalogue
@JvmOverloads
constructor(
    entries: Collection<CatalogueEntry>,
    replayTopics: Collection<ReplayTopic> = emptyList(),
    requestTopics: Collection<ReplayRequestTopic> = emptyList(),
) {
    private val byType: Map<String, CatalogueEntry> = entries.associateBy { it.eventType }
    private val replays: Map<Pair<String, String>, ReplayTopic> =
        replayTopics.associateBy { it.producer to it.subscriber }
    private val requests: Map<String, ReplayRequestTopic> = requestTopics.associateBy { it.consumer }

    init {
        require(byType.size == entries.size) { "event types must be unique in the catalogue" }
        require(replays.size == replayTopics.size) { "one replay topic per (producer, subscriber) pair" }
        require(requests.size == requestTopics.size) { "one request topic per consumer" }
    }

    /** The entry for [eventType], or `null` when the catalogue does not know it. */
    fun find(eventType: String): CatalogueEntry? = byType[eventType]

    /** Every catalogued entry. */
    fun entries(): Collection<CatalogueEntry> = byType.values

    /** Every replay topic. */
    fun replayTopics(): Collection<ReplayTopic> = replays.values

    /** Every request topic. */
    fun requestTopics(): Collection<ReplayRequestTopic> = requests.values

    /** The replay topic of the ([producer], [subscriber]) pair; [producer] may be 'every service'. */
    fun replayTopic(
        producer: String,
        subscriber: String,
    ): ReplayTopic? = replays[producer to subscriber]

    /** The replay topic named [topic], or `null`. */
    fun replayTopicNamed(topic: String): ReplayTopic? = replays.values.firstOrNull { it.topic == topic }

    /** [consumer]'s own request topic, or `null`. */
    fun requestTopicOf(consumer: String): ReplayRequestTopic? = requests[consumer]

    /** The request topics [producer] reads (named, or as one of 'every service'), in topic order. */
    fun requestTopicsReadBy(producer: String): List<ReplayRequestTopic> {
        val read = requests.values.filter { it.isReadBy(producer) }
        return read.sortedBy { it.topic }
    }

    /** The event topic named [topic], or `null`. */
    fun topic(topic: String): CatalogueEntry? = byType.values.firstOrNull { it.topic == topic }
}
