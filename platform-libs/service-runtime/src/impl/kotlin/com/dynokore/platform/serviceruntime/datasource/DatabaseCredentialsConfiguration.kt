package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import javax.sql.DataSource

/**
 * Database credentials through [SecretStore] (STK-29, INV-REQ-1231; ruling INV-Q-3909, INV-T-3913). Each part is
 * active only when its property names a [SecretRef]; without it nothing changes.
 *
 * - `service-runtime.datasource.credential`: Spring Boot's application pool (bean `dataSource`, a HikariDataSource
 *   from `spring.datasource.*`) gets a [SecretStoreCredentialsProvider] with `spring.datasource.username`, set before
 *   its pool starts.
 * - `service-runtime.flyway.credential`: Flyway's owner-role connection (`spring.flyway.user`) reads its password
 *   through SecretStore each time the migration opens a connection.
 *
 * Start-up fails when a password property is set as well: the password then comes only from the mounted secret.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseCredentialsConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [HIKARI_DATA_SOURCE])
    @ConditionalOnProperty(name = [DATASOURCE_CREDENTIAL])
    class ApplicationPool {
        companion object {
            @JvmStatic
            @Bean
            fun serviceRuntimeApplicationPoolCredentials(
                environment: Environment,
                secrets: ObjectProvider<SecretStore>,
            ): BeanPostProcessor = ApplicationPoolCredentials(environment, secrets)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [FLYWAY_CUSTOMIZER])
    @ConditionalOnProperty(name = [FLYWAY_CREDENTIAL])
    class Flyway {
        @Bean
        fun serviceRuntimeFlywayCredentials(
            environment: Environment,
            secrets: ObjectProvider<SecretStore>,
        ): FlywayConfigurationCustomizer = FlywayCredentials(environment, secrets)
    }

    companion object {
        const val DATASOURCE_CREDENTIAL = "service-runtime.datasource.credential"
        const val FLYWAY_CREDENTIAL = "service-runtime.flyway.credential"
        private const val HIKARI_DATA_SOURCE = "com.zaxxer.hikari.HikariDataSource"
        private const val FLYWAY_CUSTOMIZER = "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer"
    }
}

/** Fails start-up when any of [names] is set; the message names the properties, never a value (CS-24). */
private fun Environment.refuseAlongside(
    credential: String,
    names: List<String>,
) {
    val set = names.filter { getProperty(it) != null }
    check(set.isEmpty()) {
        "$credential takes the database password through SecretStore (STK-29), so ${set.joinToString()} must not be " +
            "set as well: remove it"
    }
}

private fun Environment.required(
    name: String,
    credential: String,
): String = getProperty(name)?.takeIf { it.isNotBlank() } ?: error("$credential is set, so $name must name the database role")

/** Sets the provider on Boot's application HikariDataSource before its pool starts (it starts on first use). */
internal class ApplicationPoolCredentials(
    private val environment: Environment,
    private val secrets: ObjectProvider<SecretStore>,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        if (beanName != APPLICATION_DATA_SOURCE) return bean
        val credential = DatabaseCredentialsConfiguration.DATASOURCE_CREDENTIAL
        // the pool itself, or the pool under its lanes (PoolLaneWiring), whichever post-processor runs first
        val pool =
            (bean as? HikariDataSource)
                ?: (bean as? DataSource)?.takeIf { it.isWrapperFor(HikariDataSource::class.java) }?.unwrap(HikariDataSource::class.java)
        check(pool != null) {
            "$credential needs Spring Boot's application DataSource to be a HikariDataSource (STK-17), " +
                "but bean '$beanName' is a ${bean.javaClass.name}"
        }
        environment.refuseAlongside(credential, listOf("spring.datasource.password", "spring.datasource.hikari.password"))
        val provider =
            SecretStoreCredentialsProvider(
                secrets.getObject(),
                environment.required("spring.datasource.username", credential),
                SecretRef(environment.getRequiredProperty(credential)),
            )
        check(!pool.isRunning) { "$credential: the application pool '$beanName' started before its credentials were set" }
        pool.credentialsProvider = provider
        return bean
    }

    private companion object {
        const val APPLICATION_DATA_SOURCE = "dataSource"
    }
}

/** Gives Flyway a connection source whose password is read through SecretStore on each connection. */
internal class FlywayCredentials(
    private val environment: Environment,
    private val secrets: ObjectProvider<SecretStore>,
) : FlywayConfigurationCustomizer {
    override fun customize(configuration: FluentConfiguration) {
        val credential = DatabaseCredentialsConfiguration.FLYWAY_CREDENTIAL
        environment.refuseAlongside(credential, listOf("spring.flyway.password"))
        val url =
            environment.getProperty("spring.flyway.url") ?: environment.getProperty("spring.datasource.url")
                ?: error("$credential is set, so spring.flyway.url or spring.datasource.url must name the database")
        val provider =
            SecretStoreCredentialsProvider(
                secrets.getObject(),
                environment.required("spring.flyway.user", credential),
                SecretRef(environment.getRequiredProperty(credential)),
            )
        configuration.dataSource(SecretStoreDriverDataSource(url, provider))
    }
}

/** An unpooled DataSource that takes each connection's credentials from [credentials] (Flyway's owner role). */
internal class SecretStoreDriverDataSource(
    private val url: String,
    private val credentials: SecretStoreCredentialsProvider,
) : AbstractDataSource() {
    override fun getConnection(): Connection {
        val current = credentials.credentials
        return DriverManager.getConnection(
            url,
            Properties().also {
                it["user"] = current.username
                it["password"] = current.password
            },
        )
    }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException("credentials come from SecretStore (${credentials.passwordRef})")

    override fun toString(): String = "SecretStoreDriverDataSource($credentials)"
}
