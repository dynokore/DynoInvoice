package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.OperatorAccessOutbox
import com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox
import com.dynokore.gst.access.adapters.persistence.JooqAuthorityVersions
import com.dynokore.gst.access.adapters.persistence.JooqOperatorKeyPublication
import com.dynokore.gst.access.adapters.structure.ReplicaTenantDirectory
import com.dynokore.gst.access.adapters.token.NimbusOperatorKeys
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.operatorsession.AuthorityVersions
import com.dynokore.gst.access.application.operatorsession.OpenOperatorSessionUseCase
import com.dynokore.gst.access.application.operatorsession.OperatorAccessPublisher
import com.dynokore.gst.access.application.operatorsession.OperatorKeyPublication
import com.dynokore.gst.access.application.operatorsession.OperatorKeys
import com.dynokore.gst.access.application.operatorsession.TenantDirectory
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** TPL-18: the wiring of `openOperatorSession` (INV-T-4154) and of the publication of its signing keys. */
@Configuration(proxyBeanMethods = false)
class OperatorSessionUseCases {
    @Bean
    fun operatorKeys(store: SecretStore): OperatorKeys = NimbusOperatorKeys(store)

    @Bean
    fun operatorSessionKeysChangedOutbox(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
    ) = OperatorSessionKeysChangedOutbox(outbox, mapper)

    @Bean
    fun operatorKeyPublication(
        dsl: DSLContext,
        tenants: PlatformTenantBinding,
        tx: TransactionRunner,
        events: OperatorSessionKeysChangedOutbox,
        environment: Environment,
    ): OperatorKeyPublication =
        JooqOperatorKeyPublication(
            dsl,
            tenants,
            tx,
            events,
            WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value,
        )

    @Bean
    fun operatorAccessPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
    ): OperatorAccessPublisher = OperatorAccessOutbox(outbox, mapper)

    @Bean
    fun authorityVersions(dsl: DSLContext): AuthorityVersions = JooqAuthorityVersions(dsl)

    @Bean
    fun tenantDirectory(structure: StructureReplica): TenantDirectory = ReplicaTenantDirectory(structure)

    @Bean
    @Suppress("LongParameterList") // one collaborator per port of the use case
    fun openOperatorSessionUseCase(
        callers: ElevationCallers,
        directory: TenantDirectory,
        keys: OperatorKeys,
        publication: OperatorKeyPublication,
        tx: TransactionRunner,
        elevations: Elevations,
        audit: GrantAudit,
        dependencies: GrantDependencies,
        settings: TokenSettings,
        clock: Clock,
        accessRecords: OperatorAccessPublisher,
        versions: AuthorityVersions,
    ) = OpenOperatorSessionUseCase(
        callers,
        directory,
        keys,
        publication,
        tx,
        elevations,
        audit,
        dependencies.matrix,
        settings,
        clock,
        accessRecords,
        versions,
    )
}
