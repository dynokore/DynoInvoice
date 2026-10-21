package com.dynokore.gst.gatewayauthz.ratelimit

import java.time.Duration

/**
 * The outcome of one protective-limit check over every policy that applies to the request (coordinator ruling
 * INV-Q-4087): each [PolicyState] gives the API-30 values of one policy (draft-ietf-httpapi-ratelimit-headers-11).
 * The request is allowed only when every policy allows it; a refusal's [retryAfter] is the reset of the exhausted
 * policy, the latest one when several are exhausted.
 */
data class RateLimitDecision(
    val policies: List<PolicyState>,
    val window: Duration,
) {
    init {
        require(policies.isNotEmpty()) { "a decision covers at least one policy" }
    }

    val allowed: Boolean get() = policies.all { it.allowed }

    val retryAfter: Duration? get() = policies.filterNot { it.allowed }.maxOfOrNull { it.reset }

    /** The state of the policy named [name]. */
    fun policy(name: String): PolicyState = policies.single { it.name == name }

    /** One policy: [quota] requests per window, [remaining] after this request and [reset] until quota frees up. */
    data class PolicyState(
        val name: String,
        val allowed: Boolean,
        val quota: Int,
        val remaining: Int,
        val reset: Duration,
    )
}
