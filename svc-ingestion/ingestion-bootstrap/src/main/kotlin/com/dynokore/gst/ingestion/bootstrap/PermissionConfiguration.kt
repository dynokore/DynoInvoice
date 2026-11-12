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
 * The one permission port of the service (OWASP ASVS V4): the caller from the verified token and the scoped
 * permission decided by the authority replica (INV-Q-10027). The replica and the operation-role map come from the
 * platform configurations that [IngestionApplication] imports.
 */
@Configuration(proxyBeanMethods = false)
class PermissionConfiguration {
    @Bean
    @ConditionalOnMissingBean(CallerSource::class)
    fun callerSource(principals: ObjectProvider<EndUserPrincipalResolver>): CallerSource =
        // the platform binds a resolver only with an identity provider; with none every request is 401 (fail closed)
        RequestCallerSource(principals.getIfAvailable { EndUserPrincipalResolver { null } })

    @Bean
    @ConditionalOnMissingBean(EntityPermission::class)
    fun entityPermission(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        environment: Environment,
    ): EntityPermission =
        ReplicaEntityPermission(
            replica.getIfAvailable(),
            tenants,
            Clock.systemUTC(),
            Duration.parse(environment.getProperty(BUDGET_PROPERTY, DEFAULT_BUDGET)),
        )

    private companion object {
        const val BUDGET_PROPERTY = "ingestion.permission.decision-budget"
        const val DEFAULT_BUDGET = "PT2S"
    }
}
