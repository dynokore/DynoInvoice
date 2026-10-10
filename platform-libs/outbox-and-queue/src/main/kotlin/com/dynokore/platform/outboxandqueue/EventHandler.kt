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
}
