package com.dynokore.platform.adapterruntime

import java.time.Instant

/**
 * One unit of work a [ResilientExecutor] gave up on after its bounded retry was
 * exhausted (INV-REQ-1353). [failureType] is the failing exception's class name only
 * — never its message, which may hold third-party detail this module cannot vouch
 * for (CS-34).
 */
data class DeadLetterEntry(
    val edge: String,
    val attempts: Int,
    val failedAt: Instant,
    val failureType: String,
) {
    init {
        require(edge.isNotBlank()) { "DeadLetterEntry.edge must not be blank" }
        require(attempts >= 1) { "DeadLetterEntry.attempts must be at least 1, was $attempts" }
    }
}
