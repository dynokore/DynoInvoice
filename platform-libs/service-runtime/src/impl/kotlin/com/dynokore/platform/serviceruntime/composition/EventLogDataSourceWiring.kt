package com.dynokore.platform.serviceruntime.composition

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import java.time.Duration
import javax.sql.DataSource

/**
 * The optional event-log pool (INV-REQ-1230 b2; owner ruling INV-Q-3955, INV-ADR-3968): when `gst.db.event-log-url`
 * names another PostgreSQL instance than `spring.datasource.url` (the HA profile, TPL-18), the [ServiceDataSources.
 * EVENT_LOG] pool connects to it as the same runtime login, with the application pool's username and password or
 * SecretStore credentials provider. At most [ServiceDataSources.EVENT_LOG_MAXIMUM] connections, and
 * [EventLogPoolProperties.minimumIdle] idle (default 1, refused above the maximum; INV-T-3977 capacity model,
 * coordinator ruling INV-Q-4348). An absent URL, or the application one, collapses into the application DataSource:
 * no bean is created and the event log runs on `dataSource`.
 *
 * It sets no connection-init SQL and no schema and issues no statement on a connection: it does not copy the application
 * pool's connectionInitSql (coordinator ruling INV-Q-4542; INV-REQ-1230: role timeouts are set by provisioning; INV-T-3982).
 *
 * The bean is not a default autowire candidate, so the service's one application `DataSource` stays the only one an
 * unqualified `DataSource` resolves to.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HikariDataSource::class)
@EnableConfigurationProperties(EventLogPoolProperties::class)
class EventLogDataSourceWiring {
    @Bean(name = [ServiceDataSources.EVENT_LOG], defaultCandidate = false)
    @Conditional(OnSeparateEventLogInstance::class)
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(name = [ServiceDataSources.EVENT_LOG])
    fun eventLogDataSource(
        dataSource: DataSource,
        properties: EventLogPoolProperties,
        environment: Environment,
    ): HikariDataSource {
        val application =
            (dataSource as? HikariDataSource)
                ?: dataSource.takeIf { it.isWrapperFor(HikariDataSource::class.java) }?.unwrap(HikariDataSource::class.java)
                ?: error("the event-log pool takes the application pool's login, so the application DataSource must be a HikariDataSource (STK-17)")
        // lazily started, as Spring Boot's application pool: no connection before the first use
        val pool = HikariDataSource()
        pool.poolName = "event-log"
        pool.jdbcUrl = environment.getRequiredProperty(EVENT_LOG_URL)
        pool.username = application.username
        pool.password = application.password
        application.credentialsProvider?.let { pool.credentialsProvider = it }
        pool.maximumPoolSize = ServiceDataSources.EVENT_LOG_MAXIMUM
        pool.minimumIdle = properties.minimumIdle
        pool.idleTimeout = IDLE_TIMEOUT.toMillis()
        pool.maxLifetime = application.maxLifetime
        pool.connectionTimeout = application.connectionTimeout
        // the pool's own name <gst.service>, set once at construction (coordinator ruling INV-Q-4487)
        environment.getProperty(SERVICE)?.takeIf { it.isNotBlank() }?.let { pool.addDataSourceProperty(APPLICATION_NAME, it) }
        return pool
    }

    /** `gst.db.event-log-url` is set and differs from `spring.datasource.url`: another instance (INV-REQ-1230 b2). */
    class OnSeparateEventLogInstance : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): Boolean {
            val eventLog = context.environment.getProperty(EVENT_LOG_URL)?.trim().orEmpty()
            val application = context.environment.getProperty(APPLICATION_URL)?.trim().orEmpty()
            return eventLog.isNotEmpty() && eventLog != application
        }
    }

    companion object {
        const val EVENT_LOG_URL = "gst.db.event-log-url"
        const val APPLICATION_URL = "spring.datasource.url"
        private const val SERVICE = "gst.service"
        private const val APPLICATION_NAME = "ApplicationName"
        private val IDLE_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
