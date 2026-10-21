package com.dynokore.gst.gatewayauthz.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The protective rate limits (INV-REQ-825 b5 and b6, owner ruling INV-Q-4048 A) over a 60-second window, with the
 * policy names of coordinator ruling INV-Q-4087: a machine token per principal under its API-23 scope's name and per
 * tenant (`tenant`); a signed-link page
 * per link (`signed-link`); an AP notification per AP adapter at the machine per-principal value (`ap-callback`,
 * INV-Q-4083). A token without a tenant is counted per principal only (INV-Q-4081). Each replica counts locally and
 * applies the configured limit divided by [readyReplicas], rounded down and at least 1 (INV-Q-4089). A refused
 * request is not counted.
 */
class ProtectiveRateLimiter(
    private val settings: RateLimitSettings,
    private val readyReplicas: Int,
    private val clock: Clock,
) {
    private val counters = ConcurrentHashMap<String, SlidingWindow>()

    init {
        require(readyReplicas >= 1) { "the ready gateway replica count must be at least 1 (INV-Q-4048 A)" }
    }

    /** A machine token of API-23 scope [scope] for [principal] of [tenant] (null when the token carries none). */
    fun checkMachine(
        scope: String,
        principal: String,
        tenant: String?,
    ): RateLimitDecision = decide(
        listOfNotNull(
            Policy(scope, "machine/principal/$principal", settings.machinePerPrincipal),
            tenant?.let { Policy(TENANT, "machine/tenant/$it", settings.machinePerTenant) },
        ),
    )

    /** A request for a signed-link page identified by [link]. */
    fun checkSignedLink(link: String): RateLimitDecision {
        val policy = Policy(SIGNED_LINK, "signed-link/$link", settings.signedLinkPerLink)
        return decide(listOf(policy))
    }

    /** An AP notification for AP adapter [adapterId], at the machine API per-principal value. */
    fun checkApNotification(adapterId: String): RateLimitDecision {
        val policy = Policy(AP_CALLBACK, "ap-callback/$adapterId", settings.machinePerPrincipal)
        return decide(listOf(policy))
    }

    /** Drops counters with no request in the window; called periodically to bound memory. */
    fun evictIdle() {
        val now = clock.instant()
        counters.entries.removeIf { it.value.isIdle(now) }
    }

    private fun decide(policies: List<Policy>): RateLimitDecision {
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

        const val TENANT = "tenant"
        const val SIGNED_LINK = "signed-link"
        const val AP_CALLBACK = "ap-callback"
    }
}
