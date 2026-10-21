package com.dynokore.gst.payload.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events payload-svc may emit (INV-REQ-815 behaviour 2): the three the payload contract publishes (the
 * ExampleHappened scaffold channel is not catalogued), each on the topic its AsyncAPI channel names. The emission
 * guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(PayloadEvents.entries)
}

object PayloadEvents {
    private const val SERVICE = "payload-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.payload."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "PayloadReady" to "gst.payload.payload-ready.v1",
            "PayloadRejected" to "gst.payload.payload-rejected.v1",
            "RenditionFindingChanged" to "gst.payload.rendition-finding-changed.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
