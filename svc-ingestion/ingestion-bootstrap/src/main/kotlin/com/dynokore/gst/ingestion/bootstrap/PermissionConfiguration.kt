package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.permission.ReplicaEntityPermission
import com.dynokore.gst.ingestion.adapters.permission.RequestCallerSource
import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun callerSource(principals: EndUserPrincipalResolver): CallerSource = RequestCallerSource(principals)

    @Bean
    @ConditionalOnMissingBean(EntityPermission::class)
    fun entityPermission(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        @Value("\${ingestion.permission.decision-budget:PT2S}") budget: String,
    ): EntityPermission =
        ReplicaEntityPermission(replica.getIfAvailable(), tenants, Clock.systemUTC(), Duration.parse(budget))
}
