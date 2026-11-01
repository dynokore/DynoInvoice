package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shares one access-svc call between identical concurrent fallback decisions (INV-Q-9083 A, INV-AC-9090). Concurrent
 * `fetch` calls with an equal key (tenant, principal id, entityRef, action) wait on one in-flight call and return its
 * result; the entry is removed when the call returns (success, Unavailable or exception), so nothing is kept: this is
 * not a cache. Each waiter honours its own [Deadline]: a waiter whose deadline ends returns
 * [FallbackResult.Unavailable] and the call goes on for the others. The counter [COALESCED_METER] rises by one for
 * each waiter that did not call.
 */
class CoalescingAuthorityFallback internal constructor(
    private val delegate: ReplicaFallback<AuthorizationRequest, AuthorityVerdict>,
    private val clock: Clock,
    private val meters: MeterRegistry?,
) : ReplicaFallback<AuthorizationRequest, AuthorityVerdict> {
    private data class Key(
        val tenant: TenantId,
        val principalId: String,
        val entityRef: String,
        val action: String,
    )

    private val inFlight = ConcurrentHashMap<Key, CompletableFuture<FallbackResult<AuthorityVerdict>>>()

    override fun fetch(
        key: AuthorizationRequest,
        deadline: Deadline,
    ): FallbackResult<AuthorityVerdict> {
        val k = Key(key.tenant, key.principal.id, key.entityRef, key.action)
        val mine = CompletableFuture<FallbackResult<AuthorityVerdict>>()
        val existing = inFlight.putIfAbsent(k, mine)
        return if (existing == null) call(k, mine, key, deadline) else await(existing, deadline)
    }

    private fun call(
        k: Key,
        mine: CompletableFuture<FallbackResult<AuthorityVerdict>>,
        request: AuthorizationRequest,
        deadline: Deadline,
    ): FallbackResult<AuthorityVerdict> {
        var result: FallbackResult<AuthorityVerdict> = FallbackResult.Unavailable
        try {
            result = delegate.fetch(request, deadline)
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: RuntimeException,
        ) {
            result = FallbackResult.Unavailable
        } finally {
            inFlight.remove(k, mine)
            mine.complete(result)
        }
        return result
    }

    private fun await(
        call: CompletableFuture<FallbackResult<AuthorityVerdict>>,
        deadline: Deadline,
    ): FallbackResult<AuthorityVerdict> {
        meters?.counter(COALESCED_METER)?.increment()
        val millis = deadline.remaining(clock).toMillis()
        if (millis <= 0) return FallbackResult.Unavailable
        return try {
            call.get(millis, TimeUnit.MILLISECONDS)
        } catch (ignored: TimeoutException) {
            FallbackResult.Unavailable
        } catch (ignored: ExecutionException) {
            FallbackResult.Unavailable
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            FallbackResult.Unavailable
        }
    }

    companion object {
        const val COALESCED_METER = "authority.fallback.coalesced"
    }
}
