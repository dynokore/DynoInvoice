package com.dynokore.platform.serviceruntime.identity

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * STK-26. [tokenPath] is the projected service-account token volume every pod
 * mounts, the same shape in both deployment forms (ARC-11). [jwkSetUri] and
 * [issuer] point at the cluster's own service-account OIDC issuer — never the
 * customer's/platform's end-user `IdentityProvider` (STK-27), a different,
 * narrower trust root entirely.
 *
 * [audience] is mandatory (INV-AC-2200): it is checked against the token's own
 * `aud` claim (Kubernetes bound service-account tokens support one), so a
 * workload token minted for a different callee is refused rather than merely
 * signature/issuer/expiry-checked and waved through. Neither the standards
 * (STK-26) nor Keel define a platform-wide default — each service is its own
 * audience — so [afterPropertiesSet] refuses to let a service start without
 * one configured, rather than silently accepting any caller's token.
 */
@ConfigurationProperties(prefix = "service-runtime.workload-identity")
data class WorkloadIdentityProperties(
    val tokenPath: String = "/var/run/secrets/tokens/workload",
    val jwkSetUri: String = "https://kubernetes.default.svc/openid/v1/jwks",
    val issuer: String? = null,
    val audience: String? = null,
) : InitializingBean {
    override fun afterPropertiesSet() {
        check(!audience.isNullOrBlank()) {
            "service-runtime.workload-identity.audience must be configured (INV-AC-2200): " +
                "each service names its own workload-token audience so a token minted for a " +
                "different callee is refused rather than accepted by default."
        }
    }
}
