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
    fun eventCatalogue(): EventCatalogue = EventCatalogue(EvidenceEvents.entries + EvidenceEvents.recordEntries)
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

    /**
     * The two record-topic types evidence-svc consumes (INV-REQ-3650 b1, platform-contracts `event-log/topics.yaml`):
     * evidence-svc is not their producer, so the emission guard refuses it to emit them. `recordTopic` makes the inbox
     * consumer skip the `docsequence` check (INV-Q-3809).
     */
    val recordEntries: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                "com.dynokore.gst.canonical.DecisionTraceRecorded.v1",
                RecordConsumerConfiguration.DECISION_TRACE_TOPIC,
                setOf("canonical-svc"),
                contentTopic = true,
                recordTopic = true,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                "com.dynokore.gst.platform.AuditEntryRecorded.v1",
                RecordConsumerConfiguration.AUDIT_ENTRY_TOPIC,
                setOf(CatalogueEntry.EVERY_SERVICE),
                contentTopic = false,
                recordTopic = true,
                retainedWhileResolvable = false,
            ),
        )
}
