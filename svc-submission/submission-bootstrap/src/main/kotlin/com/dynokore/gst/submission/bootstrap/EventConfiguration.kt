package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events submission-svc may emit (INV-REQ-815 behaviour 2): the four of model/architecture/events.yaml it produces,
 * each on the topic its AsyncAPI channel names. The emission guard of the outbox refuses any other type. The catalogue
 * also lists the one event it consumes, produced by payload-svc, so the consumer can read its topic.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(SubmissionEvents.entries + SubmissionEvents.consumed)
}

object SubmissionEvents {
    private const val SERVICE = "submission-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.submission."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "SubmissionDespatched" to "gst.submission.submission-despatched.v1",
            "SubmissionAcknowledged" to "gst.submission.submission-acknowledged.v1",
            "SubmissionFailed" to "gst.submission.submission-failed.v1",
            "ReadinessChanged" to "gst.submission.readiness-changed.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }

    const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"

    /** `PayloadReady` (topics.yaml: producer payload-svc, content topic); submission-svc is only its subscriber. */
    val consumed: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                "com.dynokore.gst.payload.PayloadReady.v1",
                PAYLOAD_READY_TOPIC,
                setOf("payload-svc"),
                contentTopic = true,
                retainedWhileResolvable = false,
            ),
        )
}
