package com.dynokore.gst.notification.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events notification-svc may emit (INV-REQ-815 behaviour 2): model/architecture/events.yaml names no event it
 * produces, so the catalogue holds the one channel the notification contract publishes, `ExampleHappened`, on the topic
 * its AsyncAPI channel names. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(NotificationEvents.entries)
}

object NotificationEvents {
    private const val SERVICE = "notification-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.notification."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "ExampleHappened" to "gst.notification.example-happened.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
