package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Reuses a [FallbackResult.Found] access-svc answer for at most [ttl] (INV-Q-9085 A, INV-AC-9180). The key is (tenant,
 * principal id, entityRef, action), compared by equality of all four. Only a Found answer is stored (NIST SP 800-53
 * AC-3; OWASP ASVS V4.1.5: an error is never turned into a grant); NotFound and Unavailable always reach the delegate
 * again. A revocation applied at access-svc at time t therefore reaches a caller by t + [ttl] at the latest. At most
 * [maxEntries] entries are held, the oldest dropped first. The age is read from the injected [Clock]. The counter
 * [CACHE_HIT_METER] rises by one for each answer served from the cache.
 */
class VerdictCachingAuthorityFallback internal constructor(
    private val delegate: ReplicaFallback<AuthorizationRequest, AuthorityVerdict>,
    private val clock: Clock,
    private val meters: MeterRegistry?,
    private val ttl: Duration,
    private val maxEntries: Int = MAX_ENTRIES,
) : ReplicaFallback<AuthorizationRequest, AuthorityVerdict> {
    private data class Key(
        val tenant: TenantId,
        val principalId: String,
        val entityRef: String,
        val action: String,
    )

    private class Entry(
        val result: FallbackResult.Found<AuthorityVerdict>,
        val receivedAt: Instant,
    )

    init {
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) {
            "service-runtime.replica.authority-fallback-cache-ttl must be above zero and at most $MAX_TTL"
        }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Insertion order: the eldest entry is the first one. */
    private val entries = LinkedHashMap<Key, Entry>()

    internal val size: Int get() = synchronized(entries) { entries.size }

    override fun fetch(
        key: AuthorizationRequest,
        deadline: Deadline,
    ): FallbackResult<AuthorityVerdict> {
        val k = Key(key.tenant, key.principal.id, key.entityRef, key.action)
        cached(k)?.let {
            meters?.counter(CACHE_HIT_METER)?.increment()
            return it
        }
        val result = delegate.fetch(key, deadline)
        if (result is FallbackResult.Found) store(k, Entry(result, clock.instant()))
        return result
    }

    private fun cached(k: Key): FallbackResult<AuthorityVerdict>? =
        synchronized(entries) {
            val entry = entries[k] ?: return null
            if (Duration.between(entry.receivedAt, clock.instant()) <= ttl) {
                entry.result
            } else {
                entries.remove(k)
                null
            }
        }

    private fun store(
        k: Key,
        entry: Entry,
    ) = synchronized(entries) {
        entries.remove(k)
        entries[k] = entry
        while (entries.size > maxEntries) {
            val eldest = entries.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    companion object {
        const val CACHE_HIT_METER = "authority.fallback.cache.hit"
        const val MAX_ENTRIES = 10_000
        val MAX_TTL: Duration = Duration.ofMillis(1000)
    }
}
