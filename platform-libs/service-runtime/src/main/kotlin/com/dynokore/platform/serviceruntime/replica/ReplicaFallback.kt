package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge

/** What a synchronous fallback call to the owning service came back with. */
sealed interface FallbackResult<out V> {
    data class Found<V>(
        val value: V,
    ) : FallbackResult<V>

    /** The owner answered and has no such thing. */
    data object NotFound : FallbackResult<Nothing>

    /** No answer: timed out, circuit open, connection refused or the call failed. */
    data object Unavailable : FallbackResult<Nothing>
}

/**
 * The one synchronous call a replica makes, to the owning service (INV-REQ-1199
 * behaviour #3: `tenancy-svc` and `access-svc` are called only as fallbacks).
 * The service supplies it over its own generated client; this module never
 * imports one.
 */
fun interface ReplicaFallback<in K, out V> {
    fun fetch(
        key: K,
        deadline: Deadline,
    ): FallbackResult<V>
}

/**
 * Runs a fallback through its edge's §36.6 policy (INV-REQ-1200 behaviour #2:
 * 500 ms timeout, one retry, set as the edge's own configuration). Every failure
 * mode, and any exception the call throws, becomes [FallbackResult.Unavailable]:
 * a replica never fails its caller because its fallback did.
 */
fun <K, V> ReplicaFallback<K, V>.guardedBy(edge: ResilientEdge): ReplicaFallback<K, V> {
    val delegate = this
    return ReplicaFallback { key, deadline ->
        try {
            edge.execute(
                deadline = deadline,
                onCallNotPermitted = { FallbackResult.Unavailable },
                onConnectFailure = { FallbackResult.Unavailable },
                onTimeout = { FallbackResult.Unavailable },
                block = { delegate.fetch(key, deadline) },
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: RuntimeException,
        ) {
            FallbackResult.Unavailable
        }
    }
}
