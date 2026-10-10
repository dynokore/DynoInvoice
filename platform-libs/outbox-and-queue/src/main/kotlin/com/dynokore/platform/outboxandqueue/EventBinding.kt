package com.dynokore.platform.outboxandqueue

/**
 * The relay's publishing port (INV-REQ-1212; INV-NOTE-3 §12.1 `EventBinding`). The PostgreSQL
 * event log is the implementation in both forms; a broker may replace it only behind this interface.
 */
interface EventBinding {
    /**
     * Inserts [envelope] into [topic]. At-least-once: the same envelope may be published again
     * after a crash, with the same id.
     *
     * @throws EventBindingUnavailable when the binding cannot accept the event now.
     */
    fun publish(
        topic: String,
        envelope: Envelope,
    )
}
