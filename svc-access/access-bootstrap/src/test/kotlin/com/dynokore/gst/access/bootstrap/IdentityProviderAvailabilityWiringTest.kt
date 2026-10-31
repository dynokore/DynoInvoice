package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.application.idp.IdentityProviderProbe
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** The settings, the schedule and the bean of the identity-provider availability (INV-T-11575, INV-Q-11557 A). */
class IdentityProviderAvailabilityWiringTest {
    private fun bind(values: Map<String, String>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("access.identity-provider.availability", IdentityProviderAvailabilityProperties::class.java)

    @Test
    fun `the defaults equal 15 s, 2 s and 3`() {
        assertThat(bind(emptyMap())).isEqualTo(
            IdentityProviderAvailabilityProperties(Duration.ofSeconds(15), Duration.ofSeconds(2), 3),
        )
    }

    @Test
    fun `a configured value replaces each default`() {
        val bound =
            bind(
                mapOf(
                    "access.identity-provider.availability.probe-interval" to "PT30S",
                    "access.identity-provider.availability.probe-timeout" to "PT5S",
                    "access.identity-provider.availability.failure-count" to "5",
                ),
            )
        assertThat(
            bound,
        ).isEqualTo(IdentityProviderAvailabilityProperties(Duration.ofSeconds(30), Duration.ofSeconds(5), 5))
    }

    @Test
    fun `a value of 0 or -1 stops the binding`() {
        listOf(
            "probe-interval" to "PT0S",
            "probe-interval" to "PT-1S",
            "probe-timeout" to "PT0S",
            "probe-timeout" to "PT-1S",
            "failure-count" to "0",
            "failure-count" to "-1",
        ).forEach { (key, value) ->
            assertThatThrownBy { bind(mapOf("access.identity-provider.availability.$key" to value)) }
                .describedAs("$key=$value")
                .isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `look-alike prefixes leave the defaults`() {
        val defaults = bind(emptyMap())
        listOf(
            "access.identity-provider.availabilities.failure-count",
            "access.identity-provider.availability-x.failure-count",
            "access.idp.availability.failure-count",
            "access.identity-provider.availabilityx.failure-count",
        ).forEach { key -> assertThat(bind(mapOf(key to "9"))).describedAs(key).isEqualTo(defaults) }
    }

    @Test
    fun `a context with a zero setting does not start`() {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(IdentityAndSync::class.java)
                .withBean(DirectoryGroupMembers::class.java, { DirectoryGroupMembers { emptySet() } })
                .withPropertyValues(
                    "access.identity-provider.issuer-uri=http://127.0.0.1:1/realms/x",
                    "access.identity-provider.directory.base-uri=http://127.0.0.1:1",
                    "access.identity-provider.directory.realm=x",
                    "access.identity-provider.directory.client-id=c",
                    "access.identity-provider.directory.client-secret-ref=s",
                    "access.idp-group-sync.interval=PT15M",
                    "access.identity-provider.availability.failure-count=0",
                )
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(
                generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }.joinToString("|"),
            ).contains("access.identity-provider.availability.failure-count must be positive")
        }
    }

    @Test
    fun `the schedule fails 3 times, then answers unavailable, and a later success answers available`() {
        val ok = AtomicBoolean(false)
        val availability = IdentityProviderAvailability("http://idp", IdentityProviderProbe { ok.get() }, 3)
        val lifecycle =
            IdentityProviderProbeLifecycle(
                availability,
                IdentityProviderAvailabilityProperties(Duration.ofMillis(20), Duration.ofSeconds(2), 3),
            )
        lifecycle.start()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (availability.isAvailable() && System.nanoTime() < deadline) Thread.sleep(5)
            assertThat(availability.isAvailable()).isFalse()
            ok.set(true)
            while (!availability.isAvailable() && System.nanoTime() < deadline) Thread.sleep(5)
            assertThat(availability.isAvailable()).isTrue()
        } finally {
            lifecycle.stop()
        }
    }

    @Test
    fun `a probe that throws does not stop the schedule`() {
        val runs =
            java.util.concurrent.atomic
                .AtomicInteger()
        val availability =
            IdentityProviderAvailability("http://idp", {
                runs.incrementAndGet()
                error("boom")
            }, 3)
        val lifecycle =
            IdentityProviderProbeLifecycle(
                availability,
                IdentityProviderAvailabilityProperties(Duration.ofMillis(10), Duration.ofSeconds(2), 3),
            )
        lifecycle.start()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (runs.get() < 5 && System.nanoTime() < deadline) Thread.sleep(5)
            assertThat(runs.get()).isGreaterThanOrEqualTo(5)
        } finally {
            lifecycle.stop()
        }
    }

    @Test
    fun `the fixture stub answers unavailable and the production bean answers available before the first probe`() {
        val production =
            IdentityAndSync().identityProviderAvailability(
                IdentityProviderProperties("http://127.0.0.1:1/realms/x"),
                IdentityProviderAvailabilityProperties(),
            )
        val stub = UnavailableIdp.build()
        assertThat(production).isNotSameAs(stub)
        assertThat(stub.isAvailable()).isFalse()
        assertThat(production.isAvailable()).isTrue()
    }
}
