package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events evidence-svc may emit (INV-REQ-815 behaviour 2): the two its AsyncAPI contract publishes,
 * EvidenceIntegrityFailed (model/architecture/events.yaml) and PriorSubmissionEvidenceImported (INV-REQ-1109), each on
 * the topic its channel names. The ExampleHappened placeholder channel is not catalogued. The emission guard of the
 * outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(EvidenceEvents.entries)
}

object EvidenceEvents {
    private const val SERVICE = "evidence-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.evidence."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "EvidenceIntegrityFailed" to "gst.evidence.evidence-integrity-failed.v1",
            "PriorSubmissionEvidenceImported" to "gst.evidence.prior-submission-evidence-imported.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}
