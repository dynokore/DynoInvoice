package com.dynokore.platform.outboxandqueue

/**
 * Writes a command's events to its service's outbox (INV-REQ-1211, ARC-05, ARC-15).
 */
interface OutboxWriter {
    /**
     * Writes one outbox row per event in the caller's open transaction and returns the
     * stamped envelopes. Assigns `id` (UUIDv7) and `docsequence` here, never in the relay
     * (S2-01). A rolled-back transaction leaves no row (INV-AC-2184).
     *
     * @throws EmissionNotPermitted when an event type is not catalogued for the emitting
     *   service (INV-AC-1869); nothing is written.
     */
    fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope>
}
