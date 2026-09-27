package com.dynokore.platform.serviceruntime

import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.diagnostics.DiagnosticsEndpoint
import com.dynokore.platform.serviceruntime.diagnostics.ErrorCodeMetrics
import com.dynokore.platform.serviceruntime.health.VaultHealthIndicator
import com.dynokore.platform.serviceruntime.logging.MdcAllowListInstaller
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * Proves `ServiceRuntimeConfiguration` activates as a genuine Spring Boot
 * auto-configuration (done-when, INV-T-2871 / T27: "Spring Boot 4.1 bootstrap
 * auto-configuration") and wires every bean this task ships, without a service's
 * own `@Import` — the classpath-alone activation `ApplicationContextRunner`
 * exercises is exactly what a real service's `bootstrap` module relies on in
 * addition to its own explicit `@Import` (TPL-18; belt and braces, not either/or).
 */
class ServiceRuntimeConfigurationTest {
    // ErrorCodeMetrics needs a MeterRegistry bean (STK-33); this repository does
    // not wire Micrometer's own auto-configuration here, only ours, so the test
    // supplies the bean directly, the same way a real service's other
    // auto-configuration (Boot's own actuator metrics export) would.
    private val contextRunner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java))

    @Test
    fun `activates on the classpath alone and wires every bean this task ships`() {
        contextRunner.run { context ->
            context.getBean(SecretStore::class.java)
            context.getBean(VaultHealthIndicator::class.java)
            context.getBean(ErrorCodeMetrics::class.java)
            context.getBean(DiagnosticsEndpoint::class.java)
            context.getBean(MdcAllowListInstaller::class.java)
        }
    }

    @Test
    fun `the SecretStore mount root is selected by configuration (ARC-11)`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("probe"), "wired-through-configuration")

        contextRunner
            .withPropertyValues("gst.bindings.secret-store.mount-root=$mountRoot")
            .run { context ->
                val store = context.getBean(SecretStore::class.java)
                store.read(SecretRef("probe")).reveal() shouldBe "wired-through-configuration"
            }
    }
}
