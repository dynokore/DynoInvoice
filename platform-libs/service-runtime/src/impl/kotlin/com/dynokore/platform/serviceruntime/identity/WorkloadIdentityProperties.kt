package com.dynokore.platform.serviceruntime.identity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * STK-26. [tokenPath] is the projected service-account token volume every pod
 * mounts, the same shape in both deployment forms (ARC-11). [jwkSetUri] and
 * [issuer] point at the cluster's own service-account OIDC issuer — never the
 * customer's/platform's end-user `IdentityProvider` (STK-27), a different,
 * narrower trust root entirely. [audience], where set, is checked against the
 * token's own `aud` claim (Kubernetes bound service-account tokens support
 * one); left unset, no audience check is added beyond signature, issuer and
 * expiry.
 */
@ConfigurationProperties(prefix = "service-runtime.workload-identity")
data class WorkloadIdentityProperties(
    val tokenPath: String = "/var/run/secrets/tokens/workload",
    val jwkSetUri: String = "https://kubernetes.default.svc/openid/v1/jwks",
    val issuer: String? = null,
    val audience: String? = null,
)
