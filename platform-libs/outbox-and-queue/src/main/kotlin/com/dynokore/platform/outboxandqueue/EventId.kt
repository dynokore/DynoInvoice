package com.dynokore.platform.outboxandqueue

/**
 * The event's identifier and idempotency key (CloudEvents `id`, FR-K.3, FR-AC.19).
 * Assigned once, when the outbox row is written (S2-01); a re-published row keeps it.
 */
@JvmInline
value class EventId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "event id must not be blank" }
    }

    override fun toString(): String = value
}
