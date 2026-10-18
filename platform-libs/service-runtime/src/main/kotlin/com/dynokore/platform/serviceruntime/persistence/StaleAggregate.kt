package com.dynokore.platform.serviceruntime.persistence

/**
 * Thrown by an adapter when [AggregateStore.update] or [AggregateStore.delete] reports a `Conflict`: the aggregate was
 * changed or deleted since the caller loaded it. The store itself never throws it; it returns the conflict. The
 * service's `ProblemMapper` maps it to SRT-008 (409, retryable; ARC-19, INV-Q-6969).
 *
 * [aggregate] is the aggregate type name and [version] the version the caller held (`before.version`). The message
 * carries neither tenant nor row content (API-22).
 */
class StaleAggregate(
    val aggregate: String,
    val version: Long,
) : RuntimeException("stale aggregate $aggregate at version $version") {
    init {
        require(aggregate.isNotBlank()) { "aggregate must not be blank" }
        require(version >= 0) { "version must not be negative" }
    }
}
