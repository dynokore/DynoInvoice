package com.dynokore.platform.outboxandqueue

/** Causation identifier carried on every event: the id of the event or request that caused it (FR-K.3). */
@JvmInline
value class CausationId(val value: String) {
    init {
        require(value.isNotBlank()) { "causation id must not be blank" }
    }

    override fun toString(): String = value
}
