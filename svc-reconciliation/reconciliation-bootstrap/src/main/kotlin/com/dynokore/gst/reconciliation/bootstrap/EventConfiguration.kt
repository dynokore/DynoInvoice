package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events reconciliation-svc may emit (INV-REQ-815 behaviour 2): the two of model/architecture/events.yaml it
 * produces (the contract's ExampleHappened is a placeholder, not catalogued), each on the topic its AsyncAPI channel
 * names. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(ReconciliationEvents.entries)
}

object ReconciliationEvents {
    private const val SERVICE = "reconciliation-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.reconciliation."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "GstReturnRecorded" to "gst.reconciliation.gst-return-recorded.v1",
            "ReconciliationCompleted" to "gst.reconciliation.reconciliation-completed.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
