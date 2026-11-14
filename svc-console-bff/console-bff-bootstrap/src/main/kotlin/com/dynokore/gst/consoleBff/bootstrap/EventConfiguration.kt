package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.LaneLimits
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events console-bff lists in its [EventCatalogue]: the canonical and master-data events it consumes, each equal
 * to its row of platform-contracts `event-log/topics.yaml`. console-bff emits no event today; the task that emits one adds its entry.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(ConsoleEvents.consumed)

    /**
     * No lane has a rate: no source names one and console-bff enqueues nothing today. service-runtime creates the one
     * `WorkQueue` from this bean; the task that owns a lane's rate adds its entry.
     */
    @Bean
    fun laneLimits(): LaneLimits = LaneLimits()
}

object ConsoleEvents {
    private const val CANONICAL = "canonical-svc"

    const val DOCUMENT_HELD_TYPE = "com.dynokore.gst.canonical.DocumentHeld.v1"
    const val DOCUMENT_HELD_TOPIC = "gst.canonical.document-held.v1"
    const val DOCUMENT_RELEASED_TYPE = "com.dynokore.gst.canonical.DocumentReleased.v1"
    const val DOCUMENT_RELEASED_TOPIC = "gst.canonical.document-released.v1"

    private const val MASTERDATA = "masterdata-svc"

    const val MASTER_DATA_RESOLVED_TYPE = "com.dynokore.gst.masterdata.MasterDataResolved.v1"
    const val MASTER_DATA_RESOLVED_TOPIC = "gst.masterdata.master-data-resolved.v1"

    val consumed: List<CatalogueEntry> =
        listOf(
            Triple(DOCUMENT_HELD_TYPE, DOCUMENT_HELD_TOPIC, CANONICAL),
            Triple(DOCUMENT_RELEASED_TYPE, DOCUMENT_RELEASED_TOPIC, CANONICAL),
            Triple(MASTER_DATA_RESOLVED_TYPE, MASTER_DATA_RESOLVED_TOPIC, MASTERDATA),
        ).map { (type, topic, producer) ->
            CatalogueEntry(
                type,
                topic,
                setOf(producer),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
        }
}
