package com.dynokore.platform.outboxandqueue

/** An event type was emitted by a service the catalogue does not name for it (INV-REQ-815, INV-AC-1869). */
class EmissionNotPermitted(
    val service: String,
    val eventType: String,
    reason: String,
) : RuntimeException("$service may not emit $eventType: $reason")
