package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.io.IOException
import java.net.URI
import java.text.ParseException
import java.time.Clock

/** `service-runtime.end-user-identity.*`: the audience end-user tokens must carry for this service (STK-27). */
@ConfigurationProperties(prefix = "service-runtime.end-user-identity")
data class EndUserIdentityProperties(
    val audience: String? = null,
)

/**
 * Wires the default [EndUserPrincipalResolver] (INV-Q-3716) when the service binds an [IdentityProvider] and
 * provides no resolver of its own. The token is verified against the binding's issuer: signature from its JWK
 * set (or the issuer's discovery document) with the last keys obtained kept for an outage ([LastKnownKeySource],
 * INV-Q-3773), issuer, expiry and audience.
 */
@AutoConfiguration
@EnableConfigurationProperties(EndUserIdentityProperties::class)
class EndUserIdentityAutoConfiguration {
    @Bean
    @ConditionalOnBean(IdentityProvider::class)
    @ConditionalOnMissingBean(EndUserPrincipalResolver::class)
    fun endUserPrincipalResolver(
        identityProvider: IdentityProvider,
        properties: EndUserIdentityProperties,
        authority: AuthorityReplica,
        clock: Clock,
    ): EndUserPrincipalResolver {
        val issuer = identityProvider.issuer()
        val remote = issuer.jwkSetUri?.let { remoteKeys(URI(it)) } ?: DiscoveredKeySource(issuer.issuerUri)
        val decoder = endUserJwtDecoder(LastKnownKeySource(remote))
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(endUserTokenValidators(issuer.issuerUri, properties)))
        return JwtEndUserPrincipalResolver(decoder, authority, clock)
    }

    /** Binds a verified operator session for the request (INV-Q-4521), after the workload-identity filter. */
    @Bean
    fun operatorSessionBindingFilter(
        resolvers: ObjectProvider<EndUserPrincipalResolver>,
    ): FilterRegistrationBean<OperatorSessionBindingFilter> =
        FilterRegistrationBean(OperatorSessionBindingFilter(resolvers)).apply {
            order = ORDER_OPERATOR_SESSION
            urlPatterns = listOf("/*")
        }

    private companion object {
        /** After the end-user token capture (-70), before any handler. */
        const val ORDER_OPERATOR_SESSION = -60
    }
}

/** A decoder over [keys] (RS256); claims are checked by the Spring validators set on it, not by Nimbus. */
fun endUserJwtDecoder(keys: JWKSource<SecurityContext>): NimbusJwtDecoder {
    val processor = DefaultJWTProcessor<SecurityContext>()
    processor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
    processor.setJWTClaimsSetVerifier { _, _ -> }
    return NimbusJwtDecoder(processor)
}

private fun remoteKeys(jwkSetUri: URI): JWKSource<SecurityContext> =
    JWKSourceBuilder.create<SecurityContext>(jwkSetUri.toURL()).build()

/**
 * The issuer's keys at the `jwks_uri` of its OIDC discovery document, looked up at the first token rather than at
 * start-up, and again until it succeeds; a failed lookup is an unreachable issuer ([KeySourceException]).
 */
private class DiscoveredKeySource(
    private val issuerUri: String,
) : JWKSource<SecurityContext> {
    @Volatile private var keys: JWKSource<SecurityContext>? = null

    override fun get(
        selector: JWKSelector,
        context: SecurityContext?,
    ): List<JWK> = (keys ?: discover().also { keys = it }).get(selector, context)

    private fun discover(): JWKSource<SecurityContext> =
        try {
            val document = URI(issuerUri.trimEnd('/') + "/.well-known/openid-configuration").toURL()
            val body = DefaultResourceRetriever().retrieveResource(document).content
            remoteKeys(URI(JSONObjectUtils.getString(JSONObjectUtils.parse(body), "jwks_uri")))
        } catch (unreachable: IOException) {
            throw KeySourceException("issuer discovery failed", unreachable)
        } catch (malformed: ParseException) {
            throw KeySourceException("issuer discovery document unreadable", malformed)
        }
}

/** Issuer, expiry and the mandatory audience (STK-27); a top-level function so it is testable without a JWK set. */
fun endUserTokenValidators(
    issuerUri: String,
    properties: EndUserIdentityProperties,
): List<OAuth2TokenValidator<Jwt>> {
    val audience =
        checkNotNull(properties.audience?.takeIf { it.isNotBlank() }) {
            "service-runtime.end-user-identity.audience must be configured when an IdentityProvider is bound (STK-27)"
        }
    return listOf(
        JwtValidators.createDefaultWithIssuer(issuerUri),
        JwtClaimValidator<Any>("aud") { claim ->
            when (claim) {
                is Collection<*> -> claim.contains(audience)
                else -> claim == audience
            }
        },
    )
}
