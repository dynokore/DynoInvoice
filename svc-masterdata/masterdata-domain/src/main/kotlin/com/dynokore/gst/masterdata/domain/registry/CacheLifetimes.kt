package com.dynokore.gst.masterdata.domain.registry

import java.time.Duration
import java.time.Instant

/**
 * How long a registry answer is served from the cache: [positive] for a found answer, [negative] for a not-found one
 * (INV-REQ-1299 for the Peppol directory, INV-REQ-1548 for the Check GST Register). A zero lifetime means no caching
 * (the catalogue note of VP-38 and VP-65). The lifetimes are read from the policy on every lookup, so a tightened
 * value applies to an entry cached before it.
 */
data class CacheLifetimes(
    val positive: Duration,
    val negative: Duration,
) {
    init {
        require(!positive.isNegative && !negative.isNegative) { "a cache lifetime is not negative" }
    }

    fun of(found: Boolean): Duration = if (found) positive else negative

    /**
     * True when an answer looked up at [lookedUpAt] is still served at [now]: strictly before its lifetime ends, so an
     * answer exactly as old as its lifetime is looked up again, and an answer with lifetime zero is never served
     * (INV-AC-2285, INV-AC-2314, INV-AC-2389).
     */
    fun serves(
        found: Boolean,
        lookedUpAt: Instant,
        now: Instant,
    ): Boolean = now.isBefore(lookedUpAt.plus(of(found)))

    companion object {
        /** 24 hours positive, 1 hour negative: the default of VP-38 and VP-65, and the most a setting may be. */
        val DEFAULT = CacheLifetimes(Duration.ofHours(24), Duration.ofHours(1))
    }
}
