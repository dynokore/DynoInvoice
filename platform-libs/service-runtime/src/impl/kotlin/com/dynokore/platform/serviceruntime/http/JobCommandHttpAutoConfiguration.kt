package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * `service-runtime.idempotency.*`: the schema holding the [IdempotencySchema] table (the service's own) and how
 * long a key is kept (API-15: at least 24 hours).
 */
@ConfigurationProperties(prefix = "service-runtime.idempotency")
data class IdempotencyProperties(
    val schema: String? = null,
    val retention: Duration = IdempotencyStore.DEFAULT_RETENTION,
)

/**
 * Serves runJobNow, pauseJob and resumeJob (INV-API-3023, INV-API-3025; INV-Q-3535) in a service that owns
 * scheduled jobs: when the service provides its [JobCommands] (its `JobRuntime`), an [EndUserPrincipalResolver]
 * over its resource-server configuration, its [TenantScope] and its application [DataSource], this registers
 * the [JobCommandServlet] at [JobCommandServlet.MAPPING] with the API-15 [IdempotencyStore] in
 * `service-runtime.idempotency.schema`. A service without jobs gets none of it.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties::class)
@ConditionalOnBean(JobCommands::class, EndUserPrincipalResolver::class, TenantScope::class, DataSource::class)
class JobCommandHttpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyStore(
        scope: TenantScope,
        dataSource: DataSource,
        clock: Clock,
        properties: IdempotencyProperties,
    ): IdempotencyStore {
        val schema =
            checkNotNull(properties.schema?.takeIf { it.isNotBlank() }) {
                "service-runtime.idempotency.schema must name the service's schema (API-15 store)"
            }
        return IdempotencyStore(scope, dataSource, null, schema, clock, properties.retention)
    }

    @Bean
    fun jobCommandServlet(
        commands: JobCommands,
        principals: EndUserPrincipalResolver,
        scope: TenantScope,
        idempotency: IdempotencyStore,
    ): ServletRegistrationBean<JobCommandServlet> = ServletRegistrationBean(JobCommandServlet(commands, principals, scope.tenants, idempotency), JobCommandServlet.MAPPING)
}
