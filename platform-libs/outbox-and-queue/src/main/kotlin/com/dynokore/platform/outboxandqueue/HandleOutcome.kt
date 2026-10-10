package com.dynokore.platform.outboxandqueue

/** What a handler did with an event (CS-08: exhaustive `when`). */
enum class HandleOutcome {
    /** The event changed state. */
    APPLIED,

    /** The event was valid but needed no state change (e.g. an older version). */
    IGNORED,
}
