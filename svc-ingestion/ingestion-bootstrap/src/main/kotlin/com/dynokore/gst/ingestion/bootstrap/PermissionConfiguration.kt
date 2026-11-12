package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.permission.ReplicaEntityPermission
import com.dynokore.gst.ingestion.adapters.permission.RequestCallerSource
import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/**
 * The one authorization wiring of the service (ARC-03, OWASP ASVS V4): the caller source and the permission port over
 * the authority replica (INV-Q-10027). The replica and the operation-role map are created by the platform
 * configurations that [IngestionApplication] imports. With no replica bound the permission refuses (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class PermissionConfiguration {
    @Bean
    @ConditionalOnMissingBean(CallerSource::class)
    fun callerSource(principals: EndUserPrincipalResolver): CallerSource = RequestCallerSource(principals)

    /** The decide budget comes from [BUDGET_PROPERTY] (12-factor III), default [DEFAULT_BUDGET]. */
    @Bean
    @ConditionalOnMissingBean(EntityPermission::class)
    fun entityPermission(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
        environment: Environment,
    ): EntityPermission =
        ReplicaEntityPermission(
            replica.getIfAvailable(),
            tenants,
            clock.getIfAvailable { Clock.systemUTC() },
            Duration.parse(environment.getProperty(BUDGET_PROPERTY, DEFAULT_BUDGET)),
        )

    companion object {
        const val BUDGET_PROPERTY = "ingestion.permission.decide-budget"
        const val DEFAULT_BUDGET = "PT1S"
    }
}
