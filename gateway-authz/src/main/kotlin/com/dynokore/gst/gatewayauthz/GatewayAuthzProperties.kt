package com.dynokore.gst.gatewayauthz

import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitSettings
import com.dynokore.gst.gatewayauthz.staleness.StalenessBound
import com.dynokore.gst.gatewayauthz.token.TokenSettings
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.resilience.Deadline
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `gateway-authz.*`, set from the api-gateway chart's installation settings (INV-T-3990): the refresh interval (at
 * most one second, INV-Q-3973 A), the staleness bound (default 30 s, tighten-only, INV-Q-4021 A), the protective
 * limits (INV-REQ-825 b5, INV-Q-4048 A) the ready gateway replica count the limits are divided by, the token issuer and
 * audience (INV-Q-4081) and the inbound identity headers removed from every admitted request (INV-REQ-1202 b5,
 * INV-Q-4099). An out-of-range
 * value fails start-up, so a raised tighten-only setting is refused, never applied.
 */
@ConfigurationProperties(prefix = "gateway-authz")
data class GatewayAuthzProperties(
    val refreshInterval: Duration = Duration.ofMillis(DEFAULT_REFRESH_MILLIS),
    val stalenessBound: Duration = StalenessBound.DEFAULT.value,
    val readyReplicas: Int = 1,
    val rateLimits: RateLimitSettings = RateLimitSettings(),
    val token: TokenSettings = TokenSettings(),
    val additionalIdentityHeaders: List<String> = emptyList(),
) {
    /**
     * The inbound identity headers removed from every admitted request (coordinator ruling INV-Q-4099): the fixed
     * internal-only headers, which a setting cannot drop (tighten-only), plus [additionalIdentityHeaders].
     */
    val identityHeaders: List<String>
        get() = (FIXED_IDENTITY_HEADERS + additionalIdentityHeaders).map { it.lowercase() }.distinct()

    companion object {
        /** STK-26 workload token and API-29 deadline: internal only (INV-Q-4099). */
        val FIXED_IDENTITY_HEADERS = listOf(WorkloadToken.HEADER_NAME, Deadline.HEADER_NAME)

        private const val DEFAULT_REFRESH_MILLIS = 500L
    }
}
