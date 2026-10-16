package com.dynokore.platform.serviceruntime.datasource

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
            (context.getBean(DataSource::class.java) as HikariDataSource).password shouldBe null
        }
    }

    @Test
    fun `a pool a BeanFactoryPostProcessor fetched before the guard was registered fails at the second pass`() {
        service.withUserConfiguration(EarlyPoolService::class.java).run { context ->
            val failure = failure(context.startupFailure)
            failure shouldContain "'earlyPool' (second pass"
            failure shouldContain "connectionInitSql (set)"
            failure shouldContain "INV-Q-4603"
            failure shouldNotContain "probe_role"
        }
    }

    @Test
    fun `a pool whose password is set in code after its first check fails at the second pass`() {
        service.withUserConfiguration(PasswordAfterCheckService::class.java).run { context ->
            val failure = failure(context.startupFailure)
            failure shouldContain "'dataSource' (second pass"
            failure shouldContain "password (set)"
            failure shouldNotContain "pw-value"
        }
    }

    @Configuration(proxyBeanMethods = false)
    class EarlyPoolService {
        companion object {
            // not an autowire candidate, so the application pool is still the auto-configured one
            @JvmStatic
            @Bean(autowireCandidate = false)
            fun earlyPool(): HikariDataSource =
                HikariDataSource().apply {
                    jdbcUrl = "jdbc:postgresql://db:5432/submission"
                    connectionInitSql = "set role probe_role"
                }

            // runs before any BeanPostProcessor is registered, so the first pass never sees earlyPool
            @JvmStatic
            @Bean
            fun earlyFetch(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { it.getBean("earlyPool") }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class PasswordAfterCheckService {
        @Bean
        fun passwordAfterCheck(dataSource: DataSource): Runnable {
            (dataSource as HikariDataSource).password = "pw-value"
            return Runnable {}
        }
    }

    private fun failure(startupFailure: Throwable?): String =
        generateSequence(startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
}
