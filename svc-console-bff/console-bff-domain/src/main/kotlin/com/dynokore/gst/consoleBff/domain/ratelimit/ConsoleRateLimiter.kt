package com.dynokore.gst.consoleBff.domain.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The console and agent protective limits (INV-REQ-825 b5 and b6, owner rulings INV-Q-4048 A and INV-Q-4085 A) over a
 * 60-second window, with the policy names of coordinator ruling INV-Q-4087: per session principal `console` and per
 * tenant `tenant`. Each replica counts locally and applies the configured limit divided by [readyReplicas], rounded
 * down and at least 1 (coordinator ruling INV-Q-4089), with no shared counter store. A refused request is not counted,
 * so it has no other effect.
 */
class ConsoleRateLimiter(
    private val settings: ConsoleRateLimitSettings,
    private val readyReplicas: Int,
    private val clock: Clock,
) {
    private val counters = ConcurrentHashMap<String, SlidingWindow>()

    init {
        require(readyReplicas >= 1) { "the ready console-bff replica count must be at least 1 (INV-Q-4048 A)" }
    }

    /** One request of [principal] of [tenant], both as resolved by the console session. */
    fun check(
        principal: String,
        tenant: String,
    ): RateLimitDecision {
        val policies =
            listOf(
                Policy(PRINCIPAL, "principal/${key(tenant)}/${key(principal)}", settings.perPrincipalFor(tenant)),
                Policy(TENANT, "tenant/${key(tenant)}", settings.perTenantFor(tenant)),
            )
        // one decision at a time: the check and the recording of an admitted request must not interleave
        synchronized(this) {
            val now = clock.instant()
            val states =
                policies.map { policy ->
                    val usage = counter(policy.key).usage(now)
                    val allowed = usage.count < policy.limit
                    RateLimitDecision.PolicyState(
                        name = policy.name,
                        allowed = allowed,
                        quota = policy.limit,
                        remaining = if (allowed) policy.limit - usage.count - 1 else 0,
                        reset = usage.reset,
                    )
                }
            val decision = RateLimitDecision(states, WINDOW)
            if (decision.allowed) policies.forEach { counter(it.key).record(now) }
            return decision
        }
    }

    /** Drops counters with no request in the window; called periodically to bound memory. */
    fun evictIdle() {
        val now = clock.instant()
        synchronized(this) { counters.entries.removeIf { it.value.isIdle(now) } }
    }

    // length-prefixed, so two different (tenant, principal) pairs never share a key
    private fun key(part: String) = "${part.length}:$part"

    private fun counter(key: String): SlidingWindow = counters.computeIfAbsent(key) { SlidingWindow(WINDOW) }

    private inner class Policy(
        val name: String,
        val key: String,
        configured: Int,
    ) {
        val limit: Int = (configured / readyReplicas).coerceAtLeast(1)
    }

    companion object {
        /** INV-REQ-825 b5: every protective limit counts over 60 seconds. */
        val WINDOW: Duration = Duration.ofSeconds(60)

        /** The per-principal policy name (coordinator ruling INV-Q-4087). */
        const val PRINCIPAL = "console"

        /** The per-tenant policy name (coordinator ruling INV-Q-4087). */
        const val TENANT = "tenant"
    }
}
