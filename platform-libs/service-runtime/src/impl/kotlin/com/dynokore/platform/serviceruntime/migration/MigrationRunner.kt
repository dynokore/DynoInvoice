package com.dynokore.platform.serviceruntime.migration

import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.JobProperties
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import com.dynokore.platform.serviceruntime.datasource.DatabaseCredentialsConfiguration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

/**
 * The migration runner (INV-REQ-1189 b5; owner ruling INV-Q-3959): the entry point of a service's migration Job. It
 * runs the service's Flyway migrations (STK-16) as the service's `<svc>_migrate` login, `spring.flyway.user`, whose
 * password it reads through SecretStore from `service-runtime.flyway.credential` (STK-29, INV-Q-3909), and its Flyway afterMigrate callback writes the
 * job-retention row from `service-runtime.jobs.retention` when the service's schema has one (coordinator rulings
 * INV-Q-4346 (A), INV-Q-5113), and exits. The service itself no longer migrates at start-up (`spring.flyway.enabled=false` in
 * service-runtime's defaults).
 *
 * It refuses to run unless `spring.flyway.user` is a `<svc>_migrate` login and `service-runtime.flyway.credential`
 * is set. `spring.flyway.url` defaults to `spring.datasource.url`.
 */
object MigrationRunner {
    const val MIGRATE_SUFFIX = "_migrate"

    @JvmStatic
    fun main(args: Array<String>) {
        run(*args).close()
    }

    /** Runs the migrations with [args] as command-line properties and returns the closed-to-be context. */
    fun run(vararg args: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(MigrationRunnerConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .initializers(ForceMigration())
            .run(*args)

    /** Flyway on, at the highest precedence: the runner exists to migrate (a service's own `false` is its start-up). */
    private class ForceMigration : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            val environment = context.environment
            val user = environment.getProperty(FLYWAY_USER)?.trim().orEmpty()
            require(user.endsWith(MIGRATE_SUFFIX) && user.length > MIGRATE_SUFFIX.length) {
                "the migration runner runs as the service's <svc>_migrate login: set $FLYWAY_USER"
            }
            require(!environment.getProperty(DatabaseCredentialsConfiguration.FLYWAY_CREDENTIAL).isNullOrBlank()) {
                "the migration runner reads the <svc>_migrate password through SecretStore: set ${DatabaseCredentialsConfiguration.FLYWAY_CREDENTIAL}"
            }
            val forced = mutableMapOf<String, Any>("spring.flyway.enabled" to "true")
            if (environment.getProperty(FLYWAY_URL).isNullOrBlank()) {
                forced[FLYWAY_URL] = environment.getRequiredProperty("spring.datasource.url")
            }
            environment.propertySources.addFirst(MapPropertySource("service-runtime-migration-runner", forced))
        }
    }

    private const val FLYWAY_USER = "spring.flyway.user"
    private const val FLYWAY_URL = "spring.flyway.url"
}

/** The runner's context: Flyway, the SecretStore binding and the credentials customizer only; no web, no wiring. */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(BindingAutoConfiguration::class, FlywayAutoConfiguration::class)
@Import(DatabaseCredentialsConfiguration::class)
@EnableConfigurationProperties(JobProperties::class, ModuleProperties::class)
class MigrationRunnerConfiguration {
    /**
     * Adds the job-retention callback (coordinator ruling INV-Q-5113): when `service-runtime.modules.schema` names a
     * schema, the location of the `afterMigrate` SQL callback and its placeholders `jobSchema` (validated by
     * [SqlIdentifier.require]) and `jobRetentionSeconds` (the digits of the positive retention in seconds). A service
     * without a schema gets neither; one whose schema has no job tables migrates and the callback writes nothing.
     */
    @Bean
    fun jobRetentionCallback(
        modules: ModuleProperties,
        jobs: JobProperties,
        environment: Environment,
    ): FlywayConfigurationCustomizer =
        FlywayConfigurationCustomizer { configuration ->
            val schema = modules.schema?.takeIf { it.isNotBlank() }
            if (schema != null) {
                require(jobs.retention.isPositive) { "the job retention must be positive" }
                // Spring Boot has bound spring.flyway.placeholders.* already and placeholders(Map) replaces the whole map
                // (INV-Q-5255): the service's own placeholders stay, a clash with the callback's two is refused
                val merged = LinkedHashMap(configuration.placeholders)
                listOf(JOB_SCHEMA, JOB_RETENTION_SECONDS).forEach {
                    check(it !in merged) { "spring.flyway.placeholders.$it is reserved for the job-retention callback" }
                }
                merged[JOB_SCHEMA] = SqlIdentifier.require(schema, "schema")
                merged[JOB_RETENTION_SECONDS] = jobs.retention.seconds.toString()
                configuration.placeholders(merged)
                // the service's own locations (Spring Boot's default when it sets none) plus the callback's
                val own = environment.getProperty(LOCATIONS, DEFAULT_LOCATION).split(',').map { it.trim() }
                configuration.locations(*(own + CALLBACK_LOCATION).toTypedArray())
                log.info("job retention callback added: {}", jobs.retention)
            }
        }

    private companion object {
        const val CALLBACK_LOCATION = "classpath:db/service-runtime-migration-runner"
        const val LOCATIONS = "spring.flyway.locations"
        const val DEFAULT_LOCATION = "classpath:db/migration"
        const val JOB_SCHEMA = "jobSchema"
        const val JOB_RETENTION_SECONDS = "jobRetentionSeconds"
        val log = LoggerFactory.getLogger(MigrationRunnerConfiguration::class.java)
    }
}
