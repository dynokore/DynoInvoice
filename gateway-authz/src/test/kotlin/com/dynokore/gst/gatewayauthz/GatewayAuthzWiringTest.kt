package com.dynokore.gst.gatewayauthz

import com.dynokore.gst.gatewayauthz.check.ExtAuthzService
import com.dynokore.gst.gatewayauthz.ratelimit.ProtectiveRateLimiter
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefreshSchedule
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialSource
import com.dynokore.gst.gatewayauthz.refresh.StubAccessSvc
import com.dynokore.gst.gatewayauthz.staleness.KeyMaterialReadiness
import com.dynokore.gst.gatewayauthz.staleness.RevocationListStaleness
import com.dynokore.gst.gatewayauthz.staleness.StalenessMetrics
import com.dynokore.gst.gatewayauthz.token.TokenSettings
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * P109: the context is built as GatewayAuthzApplication composes it (TPL-18: ServiceRuntimeConfiguration, then
 * GatewayAuthzConfiguration), with no datasource.
 */
class GatewayAuthzWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java))
            .withUserConfiguration(GatewayAuthzConfiguration::class.java)
            .withPropertyValues(
                "service-runtime.workload-identity.audience=gateway-authz",
                "gateway-authz.token.issuer=https://access.invoice.test",
                "gateway-authz.token.audience=https://api.invoice.test",
            )
            // Actuator's metrics auto-configuration provides the registry in the application.
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })

    @Test
    fun `the composition holds cache, readiness, staleness and limiter, and refreshes with a source`() {
        runner.withBean(KeyMaterialSource::class.java, { StubAccessSvc() }).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(KeyMaterialRefreshSchedule::class.java).size shouldBe 1
            context.getBean(KeyMaterialReadiness::class.java)
            context.getBean(RevocationListStaleness::class.java)
            context.getBean(MeterRegistry::class.java).find(StalenessMetrics.STALE).gauge() shouldNotBe null
            context.getBean(ProtectiveRateLimiter::class.java)
            context.getBean(ExtAuthzService::class.java)
            val cache = context.getBean(KeyMaterialCache::class.java)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!cache.loaded && System.nanoTime() < deadline) Thread.onSpinWait()
            cache.loaded shouldBe true
        }
    }

    @Test
    fun `a signed-link limit raised above its default fails start-up (INV-AC-4053)`() {
        runner.withPropertyValues("gateway-authz.rate-limits.signed-link-per-link=60").run { context ->
            (context.startupFailure != null) shouldBe true
            generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                "may only be lowered"
        }
    }

    @Test
    fun `an installation without the token issuer and audience is never ready (INV-Q-4081)`() {
        runner.withPropertyValues("gateway-authz.token.issuer=").run { context ->
            context.startupFailure shouldBe null
            context.getBean(TokenSettings.Readiness::class.java).health().status shouldBe Status.DOWN
        }
        runner.run { context ->
            context.getBean(TokenSettings.Readiness::class.java).health().status shouldBe Status.UP
        }
    }

    @Test
    fun `a setting adds identity headers but never drops the fixed ones (INV-Q-4099)`() {
        runner.withPropertyValues("gateway-authz.additional-identity-headers=X-Extra").run { context ->
            context.getBean(GatewayAuthzProperties::class.java).identityHeaders shouldBe
                listOf("x-workload-token", "x-deadline-ms", "x-extra")
        }
    }

    @Test
    fun `a staleness bound raised above 30 s fails start-up (INV-Q-4021 A)`() {
        runner.withPropertyValues("gateway-authz.staleness-bound=60s").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }
}
