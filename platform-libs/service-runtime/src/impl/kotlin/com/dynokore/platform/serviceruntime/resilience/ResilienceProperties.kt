package com.dynokore.platform.serviceruntime.resilience

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * §36.6's policy table, in configuration form (ARC-11: the values differ per
 * edge and per installation, never a code branch). [edges] is keyed by the same
 * `<caller>-><callee>` name a service asks [ResilienceRegistry.forEdge] for
 * (API-08's own `x-sync-edge`); an edge not named here gets [default] instead of
 * failing to resolve, so a newly-wired edge is safe-by-default from its first
 * call, with a merge request to give it its own row before its own performance
 * test (§13.7) confirms one. [defaultDeadlineBudget] is the fallback budget
 * `DeadlineServerFilter` starts a chain with when a request arrives with no
 * `X-Deadline-Ms` at all (INV-REQ-1203 behaviour #1) — the outermost edge of a
 * chain, not a hop within one.
 */
@ConfigurationProperties(prefix = "service-runtime.resilience")
data class ResilienceProperties(
    val defaultDeadlineBudget: Duration = Duration.ofSeconds(30),
    val default: EdgeProperties = EdgeProperties(),
    val edges: Map<String, EdgeProperties> = emptyMap(),
)

/**
 * One edge's §36.6 policy. [retry] absent means this edge is not idempotent
 * (CS-16): no retries. These bundled defaults are provisional (INV-REQ-1204
 * behaviour #5) — every real edge's own values are confirmed by the performance
 * tests of §13.7 and set here, under [ResilienceProperties.edges], by the
 * service that owns the call.
 */
data class EdgeProperties(
    val timeout: Duration = Duration.ofSeconds(5),
    val retry: RetryProperties? = null,
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties(),
    val bulkhead: BulkheadProperties = BulkheadProperties(),
) {
    fun toPolicy(): EdgePolicy = EdgePolicy(
        timeout = TimeoutPolicy(timeout),
        retry = retry?.toPolicy(),
        circuitBreaker = circuitBreaker.toPolicy(),
        bulkhead = bulkhead.toPolicy(),
    )
}

data class RetryProperties(
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(50),
    val maxBackoff: Duration = Duration.ofSeconds(2),
    val jitter: Double = 0.5,
) {
    fun toPolicy(): RetryPolicy = RetryPolicy(maxAttempts, initialBackoff, maxBackoff, jitter)
}

data class CircuitBreakerProperties(
    val failureRateThreshold: Float = 50.0F,
    val slidingWindowSize: Int = 20,
    val waitDurationInOpenState: Duration = Duration.ofSeconds(30),
    val permittedCallsInHalfOpenState: Int = 5,
) {
    fun toPolicy(): CircuitBreakerPolicy = CircuitBreakerPolicy(
        failureRateThreshold = failureRateThreshold,
        slidingWindowSize = slidingWindowSize,
        waitDurationInOpenState = waitDurationInOpenState,
        permittedCallsInHalfOpenState = permittedCallsInHalfOpenState,
    )
}

data class BulkheadProperties(
    val maxConcurrentCalls: Int = 16,
    val maxWaitDuration: Duration = Duration.ZERO,
) {
    fun toPolicy(): BulkheadPolicy = BulkheadPolicy(maxConcurrentCalls, maxWaitDuration)
}
