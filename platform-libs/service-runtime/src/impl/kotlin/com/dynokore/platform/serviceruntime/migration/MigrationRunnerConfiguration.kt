package com.dynokore.platform.serviceruntime.migration

import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.JobProperties
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import com.dynokore.platform.serviceruntime.datasource.DatabaseCredentialsConfiguration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

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
