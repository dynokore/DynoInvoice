package com.dynokore.platform.outboxandqueue

/** The event log (or broker) cannot accept events now; the relay stops, keeps its rows and retries (INV-AC-2185). */
class EventBindingUnavailable(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
