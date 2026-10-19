package com.dynokore.gst.controlPlane.adapters.events

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue

/**
 * The event types control-plane-svc may emit: the platform events every service produces (INV-NOTE-3 §18.11).
 * Events leave only through the outbox in the command transaction, and the emission guard refuses a type that is
 * not catalogued for this service (INV-REQ-1013): the contract's placeholder event has no catalogue entry, so
 * it cannot be emitted.
 */
object ControlPlaneEventCatalogue {
    const val SERVICE = "control-plane-svc"

    fun catalogue(): EventCatalogue =
        EventCatalogue(
            PlatformEventCatalogue.ALL.mapNotNull { event ->
                event.topic?.let { CatalogueEntry(event.eventType, it, setOf(CatalogueEntry.EVERY_SERVICE)) }
            },
        )

    fun guard(): EmissionGuard = EmissionGuard(catalogue(), SERVICE)
}
