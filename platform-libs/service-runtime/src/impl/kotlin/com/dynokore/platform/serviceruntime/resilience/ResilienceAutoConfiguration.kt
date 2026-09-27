package com.dynokore.platform.serviceruntime.resilience

import com.dynokore.platform.serviceruntime.resilience.http.DeadlinePropagationInterceptor
import com.dynokore.platform.serviceruntime.resilience.http.DeadlineServerFilter
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenCaptureFilter
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenPropagationInterceptor
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.time.Clock

/**
 * Wires ARC-08/STK-24's [ResilienceRegistry] and API-29's deadline propagation
 * (INV-REQ-1203, INV-REQ-1204) — the `service-runtime` half of TPL-07: a
 * service's own `adapters.clients` layer asks this registry for its edge's
 * policy, and its own generated client already carries a deadline header once
 * built from `InternalRestClientAutoConfiguration`'s `RestClient.Builder`.
 *
 * [DeadlineServerFilter] and [EndUserTokenCaptureFilter] run on every request
 * this service serves: an edge that is internal-only is reached only from
 * inside the mesh in the first place (SDS §18.14), so there is no public
 * traffic here to exempt them from by default.
 */
@AutoConfiguration
@EnableConfigurationProperties(ResilienceProperties::class)
class ResilienceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ResilienceRegistry::class)
    fun resilienceRegistry(
        properties: ResilienceProperties,
        clock: Clock,
    ): ResilienceRegistry = Resilience4jResilienceRegistry(properties, clock)

    @Bean
    @ConditionalOnMissingBean
    fun deadlinePropagationInterceptor(clock: Clock): ClientHttpRequestInterceptor = DeadlinePropagationInterceptor(clock)

    @Bean
    @ConditionalOnMissingBean
    fun endUserTokenPropagationInterceptor(): ClientHttpRequestInterceptor = EndUserTokenPropagationInterceptor()

    @Bean
    fun deadlineServerFilter(
        properties: ResilienceProperties,
        clock: Clock,
    ): FilterRegistrationBean<DeadlineServerFilter> = FilterRegistrationBean(DeadlineServerFilter(clock, properties.defaultDeadlineBudget)).apply {
        order = ORDER_DEADLINE
        urlPatterns = listOf("/*")
    }

    @Bean
    fun endUserTokenCaptureFilter(): FilterRegistrationBean<EndUserTokenCaptureFilter> = FilterRegistrationBean(EndUserTokenCaptureFilter()).apply {
        order = ORDER_END_USER_TOKEN_CAPTURE
        urlPatterns = listOf("/*")
    }

    companion object {
        // Lower runs first (Spring's own FilterRegistrationBean ordering); the
        // workload-identity filter (`IdentityAutoConfiguration`) refuses an
        // unauthenticated caller before either of these does any work.
        private const val ORDER_DEADLINE = -80
        private const val ORDER_END_USER_TOKEN_CAPTURE = -70
    }
}
