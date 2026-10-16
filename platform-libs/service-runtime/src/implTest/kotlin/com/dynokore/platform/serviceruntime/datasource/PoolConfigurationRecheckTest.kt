package com.dynokore.platform.serviceruntime.datasource

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import javax.sql.DataSource

/**
 * Coordinator ruling INV-Q-4603: [PoolConfigurationRecheck], registered by service-runtime's auto-configuration beside
 * [PoolConfigurationGuard], checks every pool again after every singleton exists, in a TPL-18 composition
 * (ApplicationContextRunner, the TPL-18 import order).
 */
class PoolConfigurationRecheckTest {
    private val service =
        tpl18Service().withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/submission",
            "spring.datasource.username=submission_runtime",
        )

    @Test
    fun `service-runtime's defaults with a TPL-16-shaped configuration start, with the second pass registered`() {
        service.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(PoolConfigurationRecheck::class.java).keys shouldBe
                setOf("serviceRuntimePoolConfigurationRecheck")
            applicationPassword(context) shouldBe null
        }
    }

    // pool-inspection read (owner ruling INV-Q-4788 (B)): a getter in a method body of this class
    private fun applicationPassword(context: ApplicationContext): String? =
        (context.getBean(DataSource::class.java) as HikariDataSource).password
}
