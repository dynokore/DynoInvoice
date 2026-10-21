package com.dynokore.gst.gatewayauthz.token

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * The installation's token settings (coordinator ruling INV-Q-4081, API-23, RFC 9068): [issuer], the configured
 * machine-token issuer (access-svc, which issues the tokens), and [audience], the installation's public API. Both are
 * required. Without them the instance stays not ready and every token is refused (fail closed); start-up itself does
 * not fail, because the image build's AOT-cache training run (STK-06, STK-53) starts the application without
 * installation settings.
 */
data class TokenSettings(
    val issuer: String = "",
    val audience: String = "",
) {
    val configured: Boolean get() = issuer.isNotBlank() && audience.isNotBlank()

    /** The `tokenSettings` health contributor, in the readiness group (application.yaml). */
    class Readiness(
        private val settings: TokenSettings,
    ) : HealthIndicator {
        override fun health(): Health = if (settings.configured) {
            Health.up().build()
        } else {
            Health.down().withDetail("reason", "gateway-authz.token.issuer and .audience are required").build()
        }
    }
}
