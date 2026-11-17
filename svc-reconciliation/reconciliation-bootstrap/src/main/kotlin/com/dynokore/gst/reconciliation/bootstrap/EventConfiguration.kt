package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events reconciliation-svc may emit (INV-REQ-815 behaviour 2): the two of model/architecture/events.yaml it
 * produces (the contract's ExampleHappened is a placeholder, not catalogued), each on the topic its AsyncAPI channel
 * names, and the platform event EvidenceArtefactRecorded of the sealed work-papers (INV-Q-24164 A). The emission guard
 * of the outbox refuses any other type.
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

    /** The platform event the service emits besides its own: the topic and type come from product-runtime. */
    private val platformEntries: List<CatalogueEntry> =
        listOf(PlatformEventCatalogue.EVIDENCE_ARTEFACT_RECORDED).map {
            CatalogueEntry(
                it.eventType,
                checkNotNull(it.topic),
                setOf(SERVICE),
                contentTopic = false,
                retainedWhileResolvable = false,
            )
        }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        } + platformEntries
}
