package com.dynokore.platform.outboxandqueue

/** The per-service relay (INV-REQ-1212): publishes the service's own outbox to the configured [EventBinding]. */
interface OutboxRelay {
    /**
     * Publishes one bounded batch of unpublished rows in outbox order, which preserves order
     * per aggregate key. Stops at the first row the binding refuses and leaves it and every
     * later row for the next run, so producers are never affected by an event-log outage.
     */
    fun runOnce(): RelayOutcome
}
