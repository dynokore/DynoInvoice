package com.dynokore.platform.serviceruntime.logging

/**
 * Carried from the ingestion request to the acknowledgement, across every
 * service (§11.6, INV-REQ-2578 behaviour #2). Safe to log: an identifier, not
 * invoice content (CS-34).
 */
@JvmInline
value class CorrelationId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "CorrelationId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Carried on each derived event, naming its parent event (§11.6, INV-REQ-2578
 * behaviour #3). Safe to log: an identifier, not invoice content (CS-34).
 */
@JvmInline
value class CausationId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "CausationId must not be blank" }
    }

    override fun toString(): String = value
}
