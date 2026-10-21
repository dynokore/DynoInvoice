package com.dynokore.gst.canonical.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events canonical-svc may emit (INV-REQ-815 behaviour 2): the six of model/architecture/events.yaml it produces,
 * each on the topic its AsyncAPI channel names. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(CanonicalEvents.entries)
}

object CanonicalEvents {
    private const val SERVICE = "canonical-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.canonical."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "TransactionRecorded" to "gst.canonical.transaction-recorded.v1",
            "CanonicalVersionCreated" to "gst.canonical.canonical-version-created.v1",
            "DecisionTraceRecorded" to "gst.canonical.decision-trace-recorded.v1",
            "DocumentHeld" to "gst.canonical.document-held.v1",
            "DocumentReleased" to "gst.canonical.document-released.v1",
            "PeriodReassigned" to "gst.canonical.period-reassigned.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
