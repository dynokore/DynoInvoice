package com.dynokore.platform.outboxandqueue

/** Correlation identifier carried on every event (FR-K.3). */
@JvmInline
value class CorrelationId(val value: String) {
    init {
        require(value.isNotBlank()) { "correlation id must not be blank" }
    }

    override fun toString(): String = value
}
