package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The event catalogue the OutboxWriter of service-runtime checks every emission against (INV-Q-10922 A). It holds the
 * platform events that have a topic; an event of control-plane-svc itself is catalogued by the task that adds it, so
 * the emission guard stays deny-by-default.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(ControlPlaneEvents.entries)
}

/** The catalogue entries of control-plane-svc: the platform events, each open to every service. */
object ControlPlaneEvents {
    val entries: List<CatalogueEntry> =
        PlatformEventCatalogue.ALL.mapNotNull { event ->
            event.topic?.let {
                CatalogueEntry(event.eventType, it, setOf(CatalogueEntry.EVERY_SERVICE), contentTopic = false)
            }
        }
}
