package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events aggregation-svc may emit (INV-REQ-815 behaviour 2): the two of model/architecture/events.yaml it
 * produces, each on the topic its AsyncAPI channel names. The scaffold placeholder `ExampleHappened` of the contract is
 * not catalogued and is not emitted. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(AggregationEvents.entries)
}

object AggregationEvents {
    private const val SERVICE = "aggregation-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.aggregation."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "AggregateReady" to "gst.aggregation.aggregate-ready.v1",
            "AggregateWindowChanged" to "gst.aggregation.aggregate-window-changed.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
