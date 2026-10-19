package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events submission-svc may emit (INV-REQ-815 behaviour 2): the four of model/architecture/events.yaml it produces,
 * each on the topic its AsyncAPI channel names. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(SubmissionEvents.entries)
}

object SubmissionEvents {
    private const val SERVICE = "submission-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.submission."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val entries: List<CatalogueEntry> =
        listOf(
            "SubmissionDespatched" to "gst.submission.submission-despatched.v1",
            "SubmissionAcknowledged" to "gst.submission.submission-acknowledged.v1",
            "SubmissionFailed" to "gst.submission.submission-failed.v1",
            "ReadinessChanged" to "gst.submission.readiness-changed.v1",
        ).map { (name, topic) ->
            CatalogueEntry("$TYPE_PREFIX$name.v1", topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
