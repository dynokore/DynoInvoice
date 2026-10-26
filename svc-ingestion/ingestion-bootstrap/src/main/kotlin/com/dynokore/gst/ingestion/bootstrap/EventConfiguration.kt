package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events ingestion-svc may emit (INV-REQ-815 behaviour 2): the two its AsyncAPI contract publishes, each on the
 * topic its channel names. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(IngestionEvents.entries)
}

object IngestionEvents {
    private const val SERVICE = "ingestion-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.ingestion."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "FeedHealthChanged" to "gst.ingestion.feed-health-changed.v1",
            "ExampleHappened" to "gst.ingestion.example-happened.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
