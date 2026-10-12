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

    /**
     * The build-only half of [append] (coordinator ruling INV-Q-4254 A): builds the row [append] would write for each
     * event, its envelope and its topic, under the same emission checks, and writes nothing. A SECURITY DEFINER
     * function that inserts a platform event together with the change it records (config-resolver's
     * `write_installation_setting`) takes the staged rows as arguments and checks them inside.
     *
     * Unsequenced platform events only: every event must be a platform type and [context] must carry no Doc UUID and
     * no aggregate version, so no row has a `docsequence` and no per-key counter is reserved.
     *
     * The default refuses, so a writer that does not build rows can never be taken for one that does.
     *
     * @throws EmissionNotPermitted when an event type is not catalogued for the emitting service (INV-AC-1869).
     * @throws IllegalArgumentException when an event is not a platform type or [context] is document-scoped or
     *   versioned.
     * @throws UnsupportedOperationException when this writer does not stage rows.
     */
    fun stage(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<StagedOutboxRow> = throw UnsupportedOperationException("${javaClass.name} does not stage outbox rows")
}

/**
 * One outbox row as [OutboxWriter.stage] built it, not written: the [topic] its event type is catalogued on and the
 * stamped [envelope] (UUIDv7 id, source, time, instance, correlation, causation; no `docsequence`).
 */
data class StagedOutboxRow(
    val topic: String,
    val envelope: Envelope,
)
