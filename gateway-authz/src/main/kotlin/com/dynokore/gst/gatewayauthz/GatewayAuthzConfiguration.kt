package com.dynokore.gst.gatewayauthz

import com.dynokore.gst.gatewayauthz.check.ExtAuthorizer
import com.dynokore.gst.gatewayauthz.check.ExtAuthzService
import com.dynokore.gst.gatewayauthz.check.RouteConfigurationFaults
import com.dynokore.gst.gatewayauthz.ratelimit.ProtectiveRateLimiter
import com.dynokore.gst.gatewayauthz.ratelimit.RateLimitEviction
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefreshSchedule
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefresher
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialSource
import com.dynokore.gst.gatewayauthz.staleness.KeyMaterialReadiness
import com.dynokore.gst.gatewayauthz.staleness.RevocationListStaleness
import com.dynokore.gst.gatewayauthz.staleness.StalenessBound
import com.dynokore.gst.gatewayauthz.staleness.StalenessMetrics
import com.dynokore.gst.gatewayauthz.token.AccessTokenVerifier
import com.dynokore.gst.gatewayauthz.token.TokenSettings
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.time.Clock

/**
 * The composition of gateway-authz (TPL-18): the key-material cache and its background refresh, readiness, the
 * staleness rule and its metrics, the protective rate limiter, the token verifier and the extAuth
 * Check service. The refresh runs when a [KeyMaterialSource] (the
 * access-svc client) is present.
 */
@Configuration(proxyBeanMethods = false)
@Import(AccessSvcConfiguration::class)
@EnableConfigurationProperties(GatewayAuthzProperties::class)
class GatewayAuthzConfiguration {
    @Bean
    fun gatewayAuthzClock(): Clock = Clock.systemUTC()

    @Bean
    fun keyMaterialCache(): KeyMaterialCache = KeyMaterialCache()

    @Bean
    fun keyMaterialRefreshSchedule(
        source: ObjectProvider<KeyMaterialSource>,
        cache: KeyMaterialCache,
        clock: Clock,
        properties: GatewayAuthzProperties,
    ): KeyMaterialRefreshSchedule? =
        source.ifAvailable?.let {
            KeyMaterialRefreshSchedule(KeyMaterialRefresher(it, cache, clock), properties.refreshInterval)
        }

    /** The `keyMaterial` health contributor, in the readiness group (application.yaml). */
    @Bean
    fun keyMaterialHealthIndicator(cache: KeyMaterialCache): KeyMaterialReadiness = KeyMaterialReadiness(cache)

    @Bean
    fun revocationListStaleness(
        cache: KeyMaterialCache,
        clock: Clock,
        properties: GatewayAuthzProperties,
        registry: ObjectProvider<MeterRegistry>,
    ): RevocationListStaleness {
        val staleness = RevocationListStaleness(cache, StalenessBound(properties.stalenessBound), clock)
        registry.ifAvailable?.let { StalenessMetrics.bind(it, staleness) }
        return staleness
    }

    @Bean
    fun protectiveRateLimiter(
        properties: GatewayAuthzProperties,
        clock: Clock,
    ): ProtectiveRateLimiter = ProtectiveRateLimiter(properties.rateLimits, properties.readyReplicas, clock)

    @Bean
    fun rateLimitEviction(limiter: ProtectiveRateLimiter): RateLimitEviction = RateLimitEviction(limiter)

    /** The `tokenSettings` health contributor, in the readiness group (application.yaml). */
    @Bean
    fun tokenSettingsHealthIndicator(properties: GatewayAuthzProperties): TokenSettings.Readiness {
        val settings = properties.token
        return TokenSettings.Readiness(settings)
    }

    @Bean
    fun accessTokenVerifier(
        properties: GatewayAuthzProperties,
        cache: KeyMaterialCache,
        clock: Clock,
    ): AccessTokenVerifier = AccessTokenVerifier(properties.token, { cache.current().keys }, clock)

    @Bean
    fun extAuthorizer(
        registry: ObjectProvider<MeterRegistry>,
        cache: KeyMaterialCache,
        verifier: AccessTokenVerifier,
        limiter: ProtectiveRateLimiter,
        properties: GatewayAuthzProperties,
    ): ExtAuthorizer =
        ExtAuthorizer(
            cache,
            verifier,
            limiter,
            properties.identityHeaders,
            RouteConfigurationFaults(registry.ifAvailable),
        )

    /** The Check service; Spring Boot's gRPC server serves every BindableService bean (INV-Q-4077). */
    @Bean
    fun extAuthzService(authorizer: ExtAuthorizer): ExtAuthzService = ExtAuthzService(authorizer)
}
