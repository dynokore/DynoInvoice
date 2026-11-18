package com.dynokore.platform.outboxandqueue

/**
 * The consumer's application-layer entry point (TPL-06 `handle`). It runs inside the
 * consumer's transaction, so its state change, any outbox rows and the inbox row commit
 * together (INV-REQ-1222). It creates state on the first event for a key whatever its
 * type (INV-REQ-1221) and has no "not found" outcome for its own aggregate.
 */
interface EventHandler {
    /** Applies [event]; throws on an infrastructure failure, which rolls the whole transaction back. */
    fun handle(event: Envelope): HandleOutcome

    /**
     * Applies [event] with the [record] it was read from (its position in the topic and the stored envelope JSON).
     * A handler that needs the position overrides this method; the one-argument method stays the contract of all
     * others, and this default forwards to it. A replayed dead letter carries [LogRecord.REPLAY_OFFSET].
     */
    fun handle(
        event: Envelope,
        record: LogRecord,
    ): HandleOutcome = handle(event)
}
