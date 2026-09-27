package com.dynokore.platform.serviceruntime.binding

/**
 * The OIDC identity source (SDS §12.1, STK-27, STK-28): the bundled platform
 * identity provider in SaaS, the customer's own directory in private. Both forms
 * are plain OIDC, so one Spring Security OAuth2 resource-server configuration
 * serves either — only the [issuer] value differs, selected by configuration
 * (ARC-11), not by a second implementation.
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1); no
 * implementation ships with this task (done-when, INV-T-2871 / T27) — one lands
 * with the task that wires a service's resource-server configuration to it, the
 * same convention this repository's own scaffold already used ("no interfaces
 * yet — those land with the Wave 1 task that specifies them").
 */
fun interface IdentityProvider : Binding {
    /** The OIDC issuer this deployment's services trust. */
    fun issuer(): OidcIssuer
}

/**
 * An OIDC issuer's discovery coordinates. [jwkSetUri] is optional: when absent, a
 * resource server discovers it from `issuerUri`'s own
 * `/.well-known/openid-configuration` document, the normal OIDC path.
 */
data class OidcIssuer(
    val issuerUri: String,
    val jwkSetUri: String? = null,
) {
    init {
        require(issuerUri.isNotBlank()) { "issuerUri must not be blank" }
    }
}
