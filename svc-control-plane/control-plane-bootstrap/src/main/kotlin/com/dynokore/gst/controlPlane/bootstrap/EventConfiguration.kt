package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.events.ControlPlaneEventCatalogue
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The event catalogue the OutboxWriter of service-runtime checks every emission against (INV-Q-10922 A). It holds the
 * platform events that have a topic; the invitation MessageRequested is the one event of control-plane-svc itself
 * (INV-Q-8677 A), so the emission guard stays deny-by-default.
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
        } + ControlPlaneEventCatalogue.own
}
