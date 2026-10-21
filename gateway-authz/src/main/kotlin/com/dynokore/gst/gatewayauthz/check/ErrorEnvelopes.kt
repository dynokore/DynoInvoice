package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitFields
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems

/**
 * The denials of gateway-authz in the API-20 error envelope that service-runtime renders (ARC-19, CS-14): 401 SRT-003
 * for a missing, invalid or revoked token, 503 SRT-011 while the issuer's key material is unavailable or stale, and
 * 429 SRT-012 for a protective limit, and 500 SRT-010 for a route configuration fault (INV-Q-4095). Each denial is
 * rendered by `Problems.render` (service-runtime 3.4.0, INV-T-4092), which sets the content type and, on the 429,
 * Retry-After.
 */
class ErrorEnvelopes {
    fun unauthenticated(correlationId: String): AuthzDecision {
        val challenge = mapOf(WWW_AUTHENTICATE to BEARER)
        return denied(ClosedErrorCode.UNAUTHENTICATED, correlationId, challenge)
    }

    fun issuerUnavailable(correlationId: String): AuthzDecision {
        val code = ClosedErrorCode.ISSUER_UNAVAILABLE
        return denied(code, correlationId)
    }

    /**
     * A route configuration fault (coordinator ruling INV-Q-4095): 500 SRT-010, no detail.
     */
    fun configurationFault(correlationId: String): AuthzDecision {
        val code = ClosedErrorCode.INTERNAL_ERROR
        return denied(code, correlationId)
    }

    /** A protective limit refusal: 429 SRT-012 with the RateLimit fields and the Retry-After in [fields]. */
    fun rateLimited(
        fields: Map<String, String>,
        correlationId: String,
    ): AuthzDecision {
        val retryAfter = fields.getValue(RateLimitFields.RETRY_AFTER).toLong()
        val others = fields - RateLimitFields.RETRY_AFTER
        return denied(ClosedErrorCode.RATE_LIMITED, correlationId, others, retryAfter)
    }

    private fun denied(
        code: ClosedErrorCode,
        correlationId: String,
        headers: Map<String, String> = emptyMap(),
        retryAfterSeconds: Long? = null,
    ): AuthzDecision {
        val response = Problems.render(code, correlationId, retryAfterSeconds = retryAfterSeconds)
        return AuthzDecision(
            allowed = false,
            status = response.status,
            code = code.code,
            headersToRemove = emptyList(),
            responseHeaders = headers + response.headers,
            body = response.body,
        )
    }

    private companion object {
        const val WWW_AUTHENTICATE = "www-authenticate"
        const val BEARER = "Bearer"
    }
}
