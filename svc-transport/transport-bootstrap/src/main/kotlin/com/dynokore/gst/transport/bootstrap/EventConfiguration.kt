package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events transport-svc may emit (INV-REQ-815 behaviour 2): the three of model/architecture/events.yaml it
 * produces, each on the topic its AsyncAPI channel names. The emission guard of the outbox refuses any other type.
 * The contract's `ExampleHappened` channel is the scaffold placeholder and is not catalogued. The platform events with
 * a fixed topic are catalogued too, produced by every service (INV-REQ-815 behaviour 4; INV-Q-11892 A), so the job
 * runtime's `JobRegistered` and `JobRunRecorded` pass the emission guard.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue =
        EventCatalogue(TransportEvents.entries + TransportEvents.consumed + PlatformEventEntries.entries)
}

object TransportEvents {
    private const val SERVICE = "transport-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.transport."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "AcknowledgementReceived" to "gst.transport.acknowledgement-received.v1",
            "NetworkDocumentReceived" to "gst.transport.network-document-received.v1",
            "AccessPointRouteChanged" to "gst.transport.access-point-route-changed.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    /** The one event transport-svc consumes (INV-Q-4993 B): the tenancy profile change, for the issuance mode only. */
    const val ENTITY_PROFILE_TOPIC = "gst.tenancy.entity-profile-changed.v1"
    const val ENTITY_PROFILE_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"

    val consumed: List<CatalogueEntry> =
        listOf(CatalogueEntry(ENTITY_PROFILE_TYPE, ENTITY_PROFILE_TOPIC, setOf("tenancy-svc"), contentTopic = false))

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }
}

/** The platform events with a fixed topic, produced by every service; the two replay messages have none. */
object PlatformEventEntries {
    val entries: List<CatalogueEntry> =
        PlatformEventCatalogue.ALL.mapNotNull { event ->
            event.topic?.let {
                CatalogueEntry(
                    event.eventType,
                    it,
                    setOf(CatalogueEntry.EVERY_SERVICE),
                    contentTopic = false,
                )
            }
        }
}
