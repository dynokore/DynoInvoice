package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.idp.KeycloakDirectoryGroupMembers
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.binding.OidcIssuer
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * `access.identity-provider.*`: the OIDC issuer of this deployment's `IdentityProvider` binding (SDS section 12.1,
 * STK-27): the platform provider in SaaS, the customer's directory in private. Set per installation by the Helm values;
 * there is no default, so a service started without an issuer does not start.
 */
@ConfigurationProperties(prefix = "access.identity-provider")
data class IdentityProviderProperties(
    val issuerUri: String,
    val jwkSetUri: String? = null,
)

/**
 * `access.identity-provider.directory.*`: where the directory groups are read (INV-Q-8833 A): the Keycloak Admin REST
 * API of the realm, with the client-credentials token of the read-only service-account client
 * `access-svc-directory-reader`. All four are required and have no default, so a service started without one does not
 * start (fail closed). [clientSecretRef] is a SecretStore reference, read-only; the secret itself is never configured.
 */
@ConfigurationProperties(prefix = "access.identity-provider.directory")
data class DirectoryProperties(
    val baseUri: String,
    val realm: String,
    val clientId: String,
    val clientSecretRef: String,
) {
    init {
        require(baseUri.isNotBlank()) { "access.identity-provider.directory.base-uri is required" }
        require(realm.isNotBlank()) { "access.identity-provider.directory.realm is required" }
        require(clientId.isNotBlank()) { "access.identity-provider.directory.client-id is required" }
        require(clientSecretRef.isNotBlank()) { "access.identity-provider.directory.client-secret-ref is required" }
    }
}

/**
 * `access.idp-group-sync.*`: how often the directory-group synchronisation runs (INV-Q-8667 A: every 15 minutes, set by
 * one required property). The value is read from `application.yaml`; there is no default in code, and a missing or
 * non-positive interval stops the binding (fail closed).
 */
@ConfigurationProperties(prefix = "access.idp-group-sync")
data class IdpGroupSyncProperties(
    val interval: Duration,
) {
    init {
        require(interval.isPositive) { "access.idp-group-sync.interval must be a positive ISO-8601 duration" }
    }
}

/**
 * TPL-18, continued: the `IdentityProvider` binding of the service (the owning service's, BindingAutoConfiguration).
 * service-runtime's `EndUserIdentityAutoConfiguration` builds the `EndUserPrincipalResolver` from it; no class of this
 * service implements that port (INV-Q-8183).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    IdentityProviderProperties::class,
    DirectoryProperties::class,
    IdpGroupSyncProperties::class,
)
class IdentityAndSync {
    @Bean
    fun identityProvider(properties: IdentityProviderProperties): IdentityProvider =
        IdentityProvider { OidcIssuer(properties.issuerUri, properties.jwkSetUri) }

    /** The Keycloak reader of directory groups; the secret is read through SecretStore at every token request. */
    @Bean
    @ConditionalOnMissingBean
    fun directoryGroupMembers(
        properties: DirectoryProperties,
        secrets: SecretStore,
    ): DirectoryGroupMembers =
        KeycloakDirectoryGroupMembers(
            properties.baseUri,
            properties.realm,
            properties.clientId,
        ) { secrets.read(SecretRef(properties.clientSecretRef)).reveal() }
}
