package com.dynokore.platform.outboxandqueue

/**
 * The replay request protocol of INV-REQ-1205 b4 (owner ruling INV-Q-3599, coordinator rulings INV-Q-3601,
 * INV-Q-3625, INV-Q-3644): its two platform event types and its two kinds of generated topic.
 */
object ReplayProtocol {
    /** Written by a consumer to its own outbox; published on its request topic (INV-API-3654). */
    const val REPLAY_REQUESTED = "com.dynokore.gst.platform.ReplayRequested.v1"

    /** The completion marker a producer writes last on the pair's replay topic (INV-API-3655). */
    const val REPLAY_COMPLETED = "com.dynokore.gst.platform.ReplayCompleted.v1"
}

/**
 * One replay topic of `event-log/topics.yaml` (`replay_topics`, INV-T-3606): the re-emitted events of [producer]'s
 * replay by scope or full sync, addressed by grant to [subscriber] alone (INV-Q-3601). [producer] is a service name or
 * [CatalogueEntry.EVERY_SERVICE] (`gst.platform.replay-<subscriber>.v1`, INV-Q-3625). [eventTypes] are the types the
 * subscriber subscribes to from that producer plus [ReplayProtocol.REPLAY_COMPLETED].
 */
data class ReplayTopic(
    val topic: String,
    val eventTypes: Set<String>,
    val producer: String,
    val subscriber: String,
    val contentTopic: Boolean,
) {
    init {
        require(ReplayProtocol.REPLAY_COMPLETED in eventTypes) { "replay topic $topic does not carry ReplayCompleted" }
    }

    /** True when [service]'s relay may insert here: the named producer, or any service for an 'every service' pair. */
    fun isProducedBy(service: String): Boolean = producer == CatalogueEntry.EVERY_SERVICE || producer == service
}

/**
 * One request topic of `event-log/topics.yaml` (`replay_request_topics`, INV-T-3606): [consumer]'s
 * `gst.<consumer>.replay-requested.v1`, which carries only [ReplayProtocol.REPLAY_REQUESTED] and is read by every
 * producer of a topic the consumer subscribes to ([producers], 'every service' counting as one value).
 */
data class ReplayRequestTopic(
    val topic: String,
    val consumer: String,
    val producers: Set<String>,
) {
    /** True when [service] reads this topic: named, or as one of 'every service'. */
    fun isReadBy(service: String): Boolean = service in producers || CatalogueEntry.EVERY_SERVICE in producers
}
