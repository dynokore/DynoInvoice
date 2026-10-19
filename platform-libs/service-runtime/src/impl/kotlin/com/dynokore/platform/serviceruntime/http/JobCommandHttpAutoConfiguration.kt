package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.composition.orConnectionless
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition
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
 * scheduled jobs, and setPolicy and acknowledgeControlRelaxation (INV-T-5180) in a service with a [PolicyWriter]: when the service provides its [JobCommands] (its `JobRuntime`), an [EndUserPrincipalResolver]
 * over its resource-server configuration, its [TenantScope] and its application [DataSource], this registers
 * the [JobCommandServlet] at [JobCommandServlet.MAPPING] with the API-15 [IdempotencyStore] in
 * `service-runtime.idempotency.schema`, keeping keys for `service-runtime.idempotency.retention` (per installation,
 * default 24 hours; a shorter one is refused at start-up, API-15). With the service's [JobRuntime], the store's purge
 * runs as the
 * [IdempotencyKeyPurgeJob] platform job (coordinator ruling INV-Q-3798). A service without jobs gets none of it.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties::class, GstProperties::class)
@ConditionalOnClass(name = ["com.dynokore.platform.outboxandqueue.TenantScope"])
@ConditionalOnBean(EndUserPrincipalResolver::class, TenantScope::class, DataSource::class)
class JobCommandHttpAutoConfiguration {
    /** The store serves the job commands and the policy commands: either one needs it (API-15). */
    class JobsOrPolicies : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN) {
        @ConditionalOnBean(JobCommands::class)
        class Jobs

        @ConditionalOnBean(PolicyWriter::class)
        class Policies
    }

    @Bean
    @Conditional(JobsOrPolicies::class)
    @ConditionalOnMissingBean
    fun idempotencyStore(
        scope: TenantScope,
        dsl: ObjectProvider<DSLContext>,
        properties: IdempotencyProperties,
    ): IdempotencyStore {
        val schema =
            checkNotNull(properties.schema?.takeIf { it.isNotBlank() }) {
                "service-runtime.idempotency.schema must name the service's schema (API-15 store)"
            }
        return IdempotencyStore(scope, dsl.orConnectionless(), schema, properties.retention)
    }

    /**
     * The key purge, a platform job of the service's [JobRuntime] (INV-Q-3798), on the runtime login through the
     * `<svc>_sys` purge function (INV-ADR-3968 item 6, INV-Q-4070).
     */
    @Bean
    @ConditionalOnBean(JobRuntime::class)
    @ConditionalOnMissingBean
    fun idempotencyKeyPurgeJob(
        runtime: JobRuntime,
        gst: GstProperties,
        store: IdempotencyStore,
    ): IdempotencyKeyPurgeJob = IdempotencyKeyPurgeJob(runtime, gst.requireService(), store)

    @Bean
    @ConditionalOnBean(JobCommands::class)
    @Suppress("LongParameterList") // a Spring @Bean factory; INV-Q-4629 (CS-50)
    fun jobCommandServlet(
        commands: JobCommands,
        principals: EndUserPrincipalResolver,
        scope: TenantScope,
        idempotency: IdempotencyStore,
        authority: ObjectProvider<AuthorityReplica>,
        clock: Clock,
    ): ServletRegistrationBean<JobCommandServlet> {
        val servlet = JobCommandServlet(commands, principals, scope, idempotency, authority.ifAvailable, clock)
        return ServletRegistrationBean(servlet, JobCommandServlet.MAPPING)
    }

    /**
     * Serves setPolicy and acknowledgeControlRelaxation (INV-T-5180, INV-Q-5172) only in a service that has a
     * config-resolver
     * [PolicyWriter]; a service without one mounts nothing, so both paths are unknown to it. The [PolicyRoleResolver]
     * is the
     * service's own port; unbound, both operations answer 403 SRT-004 (fail closed).
     */
    @Bean
    @ConditionalOnBean(PolicyWriter::class)
    fun policyCommandServlet(
        writer: PolicyWriter,
        principals: EndUserPrincipalResolver,
        scope: TenantScope,
        idempotency: IdempotencyStore,
        roles: ObjectProvider<PolicyRoleResolver>,
    ): ServletRegistrationBean<PolicyCommandServlet> {
        val servlet = PolicyCommandServlet(writer, principals, scope, idempotency, roles.ifAvailable)
        return ServletRegistrationBean(servlet, PolicyCommandServlet.MAPPING)
    }
}
