package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
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
 * `service-runtime.idempotency.schema`, keeping keys for `service-runtime.idempotency.retention` (per installation,
 * default 24 hours; a shorter one is refused at start-up, API-15). With the service's [JobRuntime] and worker
 * DataSource, the store gets the worker role and its purge runs as the [IdempotencyKeyPurgeJob] platform job
 * (coordinator ruling INV-Q-3798). A service without jobs gets none of it.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties::class, GstProperties::class)
@ConditionalOnClass(name = ["com.dynokore.platform.outboxandqueue.TenantScope"])
@ConditionalOnBean(JobCommands::class, EndUserPrincipalResolver::class, TenantScope::class, DataSource::class)
class JobCommandHttpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyStore(
        scope: TenantScope,
        @Qualifier(ServiceDataSources.WORKER) worker: ObjectProvider<DataSource>,
        clock: Clock,
        properties: IdempotencyProperties,
    ): IdempotencyStore {
        val schema =
            checkNotNull(properties.schema?.takeIf { it.isNotBlank() }) {
                "service-runtime.idempotency.schema must name the service's schema (API-15 store)"
            }
        return IdempotencyStore(scope, worker.getIfAvailable(), schema, clock, properties.retention)
    }

    /** The key purge, a platform job of the service's [JobRuntime] on its worker role (INV-Q-3798). */
    @Bean
    @ConditionalOnBean(value = [JobRuntime::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnMissingBean
    fun idempotencyKeyPurgeJob(
        runtime: JobRuntime,
        gst: GstProperties,
        store: IdempotencyStore,
    ): IdempotencyKeyPurgeJob = IdempotencyKeyPurgeJob(runtime, gst.requireService(), store)

    @Bean
    fun jobCommandServlet(
        commands: JobCommands,
        principals: EndUserPrincipalResolver,
        scope: TenantScope,
        idempotency: IdempotencyStore,
        authority: ObjectProvider<AuthorityReplica>,
        clock: Clock,
    ): ServletRegistrationBean<JobCommandServlet> {
        val servlet = JobCommandServlet(commands, principals, scope.tenants, idempotency, authority.ifAvailable, clock)
        return ServletRegistrationBean(servlet, JobCommandServlet.MAPPING)
    }
}
