package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.LaneLimits
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events console-bff lists in its [EventCatalogue]: the two canonical events it consumes, each equal to its row of
 * platform-contracts `event-log/topics.yaml`. console-bff emits no event today; the task that emits one adds its entry.
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

    val consumed: List<CatalogueEntry> =
        listOf(
            DOCUMENT_HELD_TYPE to DOCUMENT_HELD_TOPIC,
            DOCUMENT_RELEASED_TYPE to DOCUMENT_RELEASED_TOPIC,
        ).map { (type, topic) ->
            CatalogueEntry(
                type,
                topic,
                setOf(CANONICAL),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
        }
}
