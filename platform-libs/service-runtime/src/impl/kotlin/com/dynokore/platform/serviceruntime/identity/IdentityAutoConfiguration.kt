package com.dynokore.platform.serviceruntime.identity

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ResourceLoader
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.nio.file.Path

/**
 * Wires STK-26's callee side (INV-AC-2200: "verified by callee") and caller
 * side (the [WorkloadTokenPropagationInterceptor] a service's own
 * `RestClient.Builder` picks up, `InternalRestClientAutoConfiguration`).
 * [WorkloadTokenAuthenticationFilter] runs on every request by default: an
 * internal-only edge is reached only from inside the mesh in the first place
 * (SDS §18.14), so the ordinary case needs no override; a service that also
 * serves public, end-user traffic on the same port narrows its own
 * `FilterRegistrationBean` (ARC-11: differences are configuration, replacing
 * this bean, not a code branch here). It is registered on the application port
 * only: health, metrics and diagnostics are served on the separate management
 * port, which carries no token (API-33, owner ruling INV-Q-4103 A), so the
 * filter needs no exemption path.
 */
@AutoConfiguration
@EnableConfigurationProperties(WorkloadIdentityProperties::class)
class IdentityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkloadTokenSource::class)
    fun workloadTokenSource(properties: WorkloadIdentityProperties): WorkloadTokenSource =
        ProjectedServiceAccountTokenSource(Path.of(properties.tokenPath))

    @Bean
    @ConditionalOnMissingBean(WorkloadTokenVerifier::class)
    fun workloadTokenVerifier(properties: WorkloadIdentityProperties): WorkloadTokenVerifier {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri).build()
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(workloadTokenValidators(properties)))
        return JwtWorkloadTokenVerifier(decoder)
    }

    @Bean
    @ConditionalOnMissingBean
    fun workloadTokenPropagationInterceptor(tokenSource: WorkloadTokenSource): ClientHttpRequestInterceptor =
        WorkloadTokenPropagationInterceptor(tokenSource)

    @Bean
    fun workloadTokenAuthenticationFilter(
        verifier: WorkloadTokenVerifier,
        properties: WorkloadIdentityProperties,
    ): FilterRegistrationBean<WorkloadTokenAuthenticationFilter> =
        FilterRegistrationBean(WorkloadTokenAuthenticationFilter(verifier, properties.callers)).apply {
            order = ORDER_WORKLOAD_AUTHENTICATION
            urlPatterns = listOf("/*")
        }

    /** The calling service of the current request (INV-AC-8251). */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(ServiceCaller::class)
    fun serviceCaller(): ServiceCaller = RequestAttributeServiceCaller()

    /** Refuses a verified caller the contract's `x-sync-edge` of the operation does not name (INV-AC-8455). */
    @Bean
    fun syncEdgeCallerFilter(resources: ResourceLoader): FilterRegistrationBean<SyncEdgeCallerFilter> =
        FilterRegistrationBean(
            SyncEdgeCallerFilter.from(
                checkNotNull(resources.classLoader) { "no class loader to read the contract resources" },
            ),
        ).apply {
            order = ORDER_SYNC_EDGE_CALLER
            urlPatterns = listOf("/*")
        }

    companion object {
        /** After the workload authentication (-90) that captures the caller, before the deadline filter (-80). */
        const val ORDER_SYNC_EDGE_CALLER = -85

        // Runs first (Spring's own FilterRegistrationBean ordering, lower first):
        // an unauthenticated caller is refused before the deadline or end-user
        // token filters do any work (`ResilienceAutoConfiguration`).
        private const val ORDER_WORKLOAD_AUTHENTICATION = -90
    }
}

/**
 * INV-AC-2200: issuer/signature/expiry are always checked, and so — unconditionally
 * — is `aud`, against [WorkloadIdentityProperties.audience]. That property is
 * mandatory ([WorkloadIdentityProperties.afterPropertiesSet] refuses to let a
 * service start without it), so a wrong- or missing-audience token is refused
 * rather than accepted by default; [checkNotNull] here is a second line of
 * defence, never the primary one.
 *
 * A top-level function, not a private method of [IdentityAutoConfiguration], so
 * it is directly testable against a hand-built [org.springframework.security.oauth2.jwt.JwtDecoder]
 * without a live JWK-set endpoint (`IdentityAutoConfiguration`'s own bean wiring
 * needs one; a unit test does not).
 */
fun workloadTokenValidators(properties: WorkloadIdentityProperties): List<OAuth2TokenValidator<Jwt>> {
    val audience =
        checkNotNull(properties.audience?.takeIf { it.isNotBlank() }) {
            "service-runtime.workload-identity.audience must be configured (INV-AC-2200)"
        }
    return buildList {
        add(properties.issuer?.let(JwtValidators::createDefaultWithIssuer) ?: JwtValidators.createDefault())
        add(
            JwtClaimValidator<Any>("aud") { claim ->
                when (claim) {
                    is Collection<*> -> claim.contains(audience)
                    else -> claim == audience
                }
            },
        )
    }
}
