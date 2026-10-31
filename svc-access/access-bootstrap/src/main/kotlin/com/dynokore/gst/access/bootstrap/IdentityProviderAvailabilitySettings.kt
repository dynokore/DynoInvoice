package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `access.identity-provider.availability.*`: how the availability of the identity provider is probed (INV-Q-9111 C,
 * INV-Q-11557 A): one probe every [probeInterval] (15 s), each within [probeTimeout] (2 s); the provider counts as
 * unavailable after [failureCount] (3) consecutive failed probes. A value that is zero or negative stops the start
 * (fail fast, twelve-factor).
 */
@ConfigurationProperties(prefix = "access.identity-provider.availability")
data class IdentityProviderAvailabilityProperties(
    val probeInterval: Duration = Duration.ofSeconds(DEFAULT_INTERVAL_SECONDS),
    val probeTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val failureCount: Int = IdentityProviderAvailability.DEFAULT_FAILURE_LIMIT,
) {
    init {
        require(probeInterval.isPositive) { "access.identity-provider.availability.probe-interval must be positive" }
        require(probeTimeout.isPositive) { "access.identity-provider.availability.probe-timeout must be positive" }
        require(failureCount > 0) { "access.identity-provider.availability.failure-count must be positive" }
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 15L
        const val DEFAULT_TIMEOUT_SECONDS = 2L
    }
}

/**
 * Runs one probe of the identity provider every `access.identity-provider.availability.probe-interval`, after the
 * first interval. A probe never throws (the availability counts an exception as a failed probe); the guard keeps the
 * schedule alive whatever happens.
 */
class IdentityProviderProbeLifecycle(
    private val availability: IdentityProviderAvailability,
    private val properties: IdentityProviderAvailabilityProperties,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        val pool =
            Executors.newSingleThreadScheduledExecutor {
                Thread(
                    it,
                    "idp-availability-probe",
                ).apply { isDaemon = true }
            }
        val millis = properties.probeInterval.toMillis()
        pool.scheduleWithFixedDelay({ runCatching { availability.probeOnce() } }, millis, millis, TimeUnit.MILLISECONDS)
        executor = pool
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null
}

/** TPL-18, continued: the probe schedule of the identity-provider availability (INV-T-11575). */
@Configuration(proxyBeanMethods = false)
class IdentityProviderProbeJob {
    @Bean
    fun identityProviderProbeLifecycle(
        availability: IdentityProviderAvailability,
        properties: IdentityProviderAvailabilityProperties,
    ) = IdentityProviderProbeLifecycle(availability, properties)
}
