package com.dynokore.gst.gatewayauthz.check

import com.dynokore.gst.gatewayauthz.ratelimit.ProtectiveRateLimiter
import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitDecision
import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitFields
import com.dynokore.gst.gatewayauthz.ratelimit.RouteClass
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.token.AccessTokenVerifier
import com.dynokore.gst.gatewayauthz.token.VerifiedToken
import java.time.Clock

/**
 * The decision of one Envoy Check request (INV-T-4056), with no call leaving the pod: the route class from the
 * per-route context (coordinator ruling INV-Q-4083, unknown refused); on the machine API a bearer token checked by
 * [verifier] (INV-Q-4081) and against the cached revocation list (INV-Q-4008) and the protective limits (INV-REQ-825 b5
 * and b6); on the signed-link page and AP notification routes no token, a limit per link or per AP adapter; on console
 * routes neither (owner ruling INV-Q-4085 A). The internal-only headers are removed from every admitted request
 * (INV-Q-4099).
 */
class ExtAuthorizer(
    private val cache: KeyMaterialCache,
    private val verifier: AccessTokenVerifier,
    private val limiter: ProtectiveRateLimiter,
    private val identityHeaders: List<String>,
    private val faults: RouteConfigurationFaults,
) {
    private val envelopes = ErrorEnvelopes()
    private val clock: Clock = verifier.clock

    fun check(request: AuthzRequest): AuthzDecision {
        val routeClass = RouteClass.fromWire(request.context[AuthzRequest.ROUTE_CLASS])
        return when (routeClass) {
            RouteClass.MACHINE_API -> machineApi(request)

            RouteClass.SIGNED_LINK -> tokenless(request, ::signedLink)

            RouteClass.AP_NOTIFICATION -> tokenless(request, ::apNotification)

            // Console routes: no token check and no limit at the gateway (owner ruling INV-Q-4085 A).
            RouteClass.CONSOLE -> AuthzDecision(true, HTTP_OK, null, identityHeaders, emptyMap(), "")

            null -> configurationFault(request)
        }
    }

    private fun machineApi(request: AuthzRequest): AuthzDecision {
        val bearer = bearerOf(request.headers["authorization"])
        val token = bearer?.let(verifier::verify)
        return when {
            !cache.loaded -> {
                envelopes.issuerUnavailable(request.correlationId)
            }

            token == null -> {
                envelopes.unauthenticated(request.correlationId)
            }

            cache.current().revocations.refuses(token.identity, clock.instant()) -> {
                envelopes.unauthenticated(request.correlationId)
            }

            else -> {
                scopeChecked(request, token)
            }
        }
    }

    /** The scope check (INV-Q-9038 A): after the token checks, before the limiter. */
    private fun scopeChecked(
        request: AuthzRequest,
        token: VerifiedToken,
    ): AuthzDecision {
        val required = request.context[AuthzRequest.REQUIRED_SCOPE]
        val also = request.context[AuthzRequest.ALSO_SCOPE]
        val configured =
            required in VerifiedToken.MACHINE_SCOPES &&
                (also == null || (also == CERTIFICATION && required == INGEST))
        return when {
            !configured -> {
                configurationFault(request)
            }

            !admits(token, required, also) -> {
                envelopes.accessDenied(request.correlationId)
            }

            else -> {
                val decision = limiter.checkMachine(token.machineScope, token.principal, token.tenantId)
                limited(decision, request.correlationId)
            }
        }
    }

    /** Equal scope, or `certification` on a route that names it, in a SANDBOX token (INV-Q-9032 A). */
    private fun admits(
        token: VerifiedToken,
        required: String?,
        also: String?,
    ): Boolean =
        token.machineScope == required ||
            (token.machineScope == CERTIFICATION && also == CERTIFICATION && token.environment == SANDBOX)

    private fun tokenless(
        request: AuthzRequest,
        limit: (AuthzRequest) -> RateLimitDecision?,
    ): AuthzDecision = limit(request)?.let { limited(it, request.correlationId) } ?: configurationFault(request)

    private fun configurationFault(request: AuthzRequest): AuthzDecision {
        faults.record(request)
        return envelopes.configurationFault(request.correlationId)
    }

    private fun signedLink(request: AuthzRequest): RateLimitDecision? {
        val parameter = request.context[AuthzRequest.LINK_PARAMETER] ?: return null
        return pathParameter(request, parameter)?.let(limiter::checkSignedLink)
    }

    private fun apNotification(request: AuthzRequest): RateLimitDecision? {
        val adapterId = pathParameter(request, AuthzRequest.ADAPTER_PARAMETER)
        return adapterId?.let(limiter::checkApNotification)
    }

    private fun pathParameter(
        request: AuthzRequest,
        parameter: String,
    ): String? {
        val template = request.context[AuthzRequest.PATH_TEMPLATE] ?: return null
        return PathTemplate.parameter(template, request.path, parameter)
    }

    private fun limited(
        decision: RateLimitDecision,
        correlationId: String,
    ): AuthzDecision {
        val fields = RateLimitFields.of(decision)
        return if (decision.allowed) {
            AuthzDecision(true, HTTP_OK, null, identityHeaders, fields, "")
        } else {
            envelopes.rateLimited(fields, correlationId)
        }
    }

    private fun bearerOf(authorization: String?): String? {
        val parts = authorization?.trim()?.split(' ', limit = 2) ?: return null
        return parts
            .takeIf { it.size == 2 && it[0].equals(BEARER, ignoreCase = true) }
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val BEARER = "Bearer"
        const val HTTP_OK = 200
        const val INGEST = "ingest"
        const val CERTIFICATION = "certification"
        const val SANDBOX = "SANDBOX"
    }
}
