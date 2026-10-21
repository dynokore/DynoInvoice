package com.dynokore.gst.gatewayauthz.staleness

import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import java.time.Clock
import java.time.Duration

/**
 * The staleness of the revocation list (owner ruling INV-Q-4021 A, INV-Q-4085 A): once it is older than [bound],
 * machine tokens keep verifying from the cache and the staleness metric drives the alert; no route answers 503 for
 * staleness, because the gateway validates tokens only on the machine public API. The list's age is measured from the
 * start of its last complete read.
 */
class RevocationListStaleness(
    private val cache: KeyMaterialCache,
    private val bound: StalenessBound,
    private val clock: Clock,
) {
    /** The list's age, or null before the first complete read. */
    fun age(): Duration? = cache.current().listFreshAt?.let { Duration.between(it, clock.instant()) }

    fun isStale(): Boolean = age()?.let { it > bound.value } ?: true
}
