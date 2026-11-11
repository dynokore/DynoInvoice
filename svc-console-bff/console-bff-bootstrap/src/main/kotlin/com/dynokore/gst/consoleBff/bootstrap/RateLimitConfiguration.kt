package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ratelimit.ConsoleRateLimitFilter
import com.dynokore.gst.consoleBff.adapters.ratelimit.RateLimitEviction
import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimiter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The console protective-limit wiring (TPL-18): limiter, eviction and the filter right after the session filter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleRateLimitProperties::class)
class RateLimitConfiguration {
    @Bean
    fun consoleRateLimiter(
        properties: ConsoleRateLimitProperties,
        clock: Clock,
    ) = ConsoleRateLimiter(properties.settings(), properties.readyReplicas, clock)

    @Bean
    fun rateLimitEviction(limiter: ConsoleRateLimiter) = RateLimitEviction(limiter)

    @Bean
    fun consoleRateLimitFilter(limiter: ConsoleRateLimiter) = ConsoleRateLimitFilter(limiter)

    @Bean
    fun consoleRateLimitFilterRegistration(filter: ConsoleRateLimitFilter) =
        FilterRegistrationBean(filter).apply {
            order = ConsoleRateLimitFilter.ORDER
            addUrlPatterns("/*")
        }
}
