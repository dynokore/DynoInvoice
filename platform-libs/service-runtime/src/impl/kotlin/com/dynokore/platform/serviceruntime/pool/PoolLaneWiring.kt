package com.dynokore.platform.serviceruntime.pool

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Splits Spring Boot's application pool (bean `dataSource`, a HikariDataSource, STK-17) into lanes before it starts
 * (INV-REQ-1230 b1, INV-ADR-3968 item 1): the budgets come from `service-runtime.pool.*` ([PoolLaneProperties]), the
 * pool maximum is set to their sum, and the bean becomes a [LanedDataSource] over the pool. Start-up fails when no
 * budget can be determined, or when `spring.datasource.hikari.maximum-pool-size` is set to another value than the sum.
 * A `dataSource` that is not a HikariDataSource (a test double) is left as it is.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HikariDataSource::class)
class PoolLaneWiring {
    companion object {
        @JvmStatic
        @Bean
        fun serviceRuntimePoolLanes(environment: Environment): BeanPostProcessor = PoolLanePostProcessor(environment)
    }
}

internal class PoolLanePostProcessor(
    private val environment: Environment,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        if (beanName != APPLICATION_DATA_SOURCE || bean !is HikariDataSource) return bean
        val properties = Binder.get(environment).bindOrCreate(PoolLaneProperties.PREFIX, PoolLaneProperties::class.java)
        val budgets = properties.budgets()
        environment.getProperty(MAXIMUM_POOL_SIZE, Int::class.java)?.let { configured ->
            check(configured == budgets.maximum) {
                "$MAXIMUM_POOL_SIZE is $configured, but the application pool's maximum is the sum of its lane " +
                    "budgets " +
                    "(request / background / reprocessing $budgets = ${budgets.maximum}): set ${PoolLaneProperties.PREFIX}.lanes.* instead"
            }
        }
        check(!bean.isRunning) { "the application pool '$beanName' started before its lanes were set" }
        bean.maximumPoolSize = budgets.maximum
        // the pool's own name, set once at construction; a transaction names its lane on top (INV-Q-4487)
        environment
            .getProperty(
                SERVICE,
            )?.takeIf { it.isNotBlank() }
            ?.let { bean.addDataSourceProperty(APPLICATION_NAME, it) }
        return LanedDataSource(bean, budgets)
    }

    private companion object {
        const val APPLICATION_DATA_SOURCE = "dataSource"
        const val SERVICE = "gst.service"
        const val APPLICATION_NAME = "ApplicationName"
        const val MAXIMUM_POOL_SIZE = "spring.datasource.hikari.maximum-pool-size"
    }
}
