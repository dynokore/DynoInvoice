package com.dynokore.platform.tenancyjooq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The test-fixtures variant registers TenancyProbe as a Spring bean over the application's `dataSource` through a test
 * auto-configuration, as TPL-14 injects it (`@Autowired val probe: TenancyProbe`; coordinator ruling INV-Q-4140). The
 * data source is configured, never connected: the bean wiring is what is proved here.
 */
class TenancyProbeAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration::class.java, TenancyProbeAutoConfiguration::class.java),
        )

    @Test
    fun `the test fixtures list the auto-configuration`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates
        assertTrue(TenancyProbeAutoConfiguration::class.java.name in candidates, candidates.toString())
    }

    @Test
    fun `TenancyProbe is a bean over the application data source`() {
        runner
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/service",
                "spring.datasource.username=service_app",
            ).run { context ->
                assertEquals(1, context.getBeansOfType(TenancyProbe::class.java).size)
            }
    }

    @Test
    fun `there is no TenancyProbe without an application data source`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenancyProbeAutoConfiguration::class.java))
            .run { context -> assertEquals(0, context.getBeansOfType(TenancyProbe::class.java).size) }
    }
}
