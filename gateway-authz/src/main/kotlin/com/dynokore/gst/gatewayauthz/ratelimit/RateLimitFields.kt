package com.dynokore.gst.gatewayauthz.ratelimit

import java.time.Duration

/**
 * The API-30 fields of draft-ietf-httpapi-ratelimit-headers-11, listing every policy that applies (coordinator ruling
 * INV-Q-4087), for example `RateLimit-Policy: "ingest";q=600;w=60, "tenant";q=1200;w=60` and
 * `RateLimit: "ingest";r=0;t=30, "tenant";r=598;t=58`, carried by refusals and allowed responses alike, and
 * Retry-After on a refusal (INV-REQ-825 b1 and b6). Seconds are rounded up, so a client never retries before quota
 * frees up.
 */
object RateLimitFields {
    const val RATE_LIMIT_POLICY = "RateLimit-Policy"
    const val RATE_LIMIT = "RateLimit"
    const val RETRY_AFTER = "Retry-After"

    /** The fields for [decision]. */
    fun of(decision: RateLimitDecision): Map<String, String> {
        val window = seconds(decision.window)
        val fields =
            linkedMapOf(
                RATE_LIMIT_POLICY to decision.policies.joinToString(", ") { "${name(it)};q=${it.quota};w=$window" },
                RATE_LIMIT to
                    decision.policies.joinToString(", ") { "${name(it)};r=${it.remaining};t=${seconds(it.reset)}" },
            )
        decision.retryAfter?.let { fields[RETRY_AFTER] = seconds(it).coerceAtLeast(1).toString() }
        return fields
    }

    private fun name(policy: RateLimitDecision.PolicyState): String {
        val name = policy.name
        require(name.isNotEmpty() && name.all { it in ' '..'~' && it != '"' && it != '\\' }) {
            "a RateLimit policy name is a printable ASCII sf-string without quotes or backslashes"
        }
        return "\"$name\""
    }

    private fun seconds(duration: Duration): Long {
        val whole = duration.seconds
        return if (duration.nano > 0) whole + 1 else whole
    }
}
