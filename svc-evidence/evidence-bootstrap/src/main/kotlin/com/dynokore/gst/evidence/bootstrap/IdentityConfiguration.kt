package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.binding.OidcIssuer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `evidence.identity-provider.*`: the OIDC issuer of this deployment's `IdentityProvider` binding (STK-27). Set per
 * installation by the Helm values; there is no default, so a service started without an issuer does not start. The
 * audience is the library property `service-runtime.end-user-identity.audience`, also without a default.
 */
@ConfigurationProperties(prefix = "evidence.identity-provider")
class IdentityProviderProperties(
    issuerUri: String?,
    val jwkSetUri: String? = null,
) {
    /** Never null or blank: a missing or blank issuer stops the binding (fail closed). */
    val issuerUri: String = issuerUri?.takeIf { it.isNotBlank() } ?: error(ISSUER_REQUIRED)

    init {
        require(jwkSetUri == null || jwkSetUri.isNotBlank()) {
            "evidence.identity-provider.jwk-set-uri must not be blank when set"
        }
    }

    private companion object {
        const val ISSUER_REQUIRED = "evidence.identity-provider.issuer-uri is required and must not be blank"
    }
}

/**
 * TPL-18, continued: the one `IdentityProvider` binding of the service. service-runtime's
 * `EndUserIdentityAutoConfiguration` builds the `EndUserPrincipalResolver` from it, and the shared `setPolicy` decides
 * by permission through `AuthorityReplica.decide`; no class of this service implements either port (INV-Q-12588,
 * INV-Q-10027).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProviderProperties::class)
class IdentityConfiguration {
    @Bean
    fun identityProvider(properties: IdentityProviderProperties): IdentityProvider =
        IdentityProvider { OidcIssuer(properties.issuerUri, properties.jwkSetUri) }
}
