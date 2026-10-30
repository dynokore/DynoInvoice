package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.breakglass.VariationPointBreakGlassSettings
import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassSessions
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqIdpGroupMappings
import com.dynokore.gst.access.adapters.persistence.JooqMappedGrants
import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.BreakGlassSettings
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdpGroupCollaborators
import com.dynokore.gst.access.application.idp.IdpGroupMappings
import com.dynokore.gst.access.application.idp.IdpProvisioningEffects
import com.dynokore.gst.access.application.idp.MapIdpGroupUseCase
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.application.idp.UnmapIdpGroupUseCase
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, continued: the use cases of the directory-group mappings (INV-REQ-892, INV-REQ-1263) and of break-glass
 * (INV-REQ-896, INV-REQ-897, INV-REQ-1269), constructed here so that [UseCases] stays within the function limit.
 */
@Configuration(proxyBeanMethods = false)
class IdpAndBreakGlassUseCases {
    @Bean
    fun idpGroupMappings(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): IdpGroupMappings = JooqIdpGroupMappings(dsl, tenants, clock)

    @Bean
    fun idpGroupCollaborators(
        tx: TransactionRunner,
        mappings: IdpGroupMappings,
        members: DirectoryGroupMembers,
        clock: Clock,
    ) = IdpGroupCollaborators(tx, mappings, members, clock)

    @Bean
    fun mappedGrants(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        consent: ConsentCheck,
    ): MappedGrants = JooqMappedGrants(dsl, JooqGrantRepository(dsl, tenants, clock, consent))

    @Bean
    fun idpProvisioningEffects(
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
        catalogue: RoleCatalogue,
        mapped: MappedGrants,
        conflictingPairs: ConflictingRolePairs,
    ) = IdpProvisioningEffects(publisher, audit, catalogue, mapped, conflictingPairs)

    @Bean
    fun mapIdpGroupUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = MapIdpGroupUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)

    @Bean
    fun unmapIdpGroupUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = UnmapIdpGroupUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)

    @Bean
    fun syncIdpGroupsUseCase(
        shared: IdpGroupCollaborators,
        dependencies: GrantDependencies,
        effects: IdpProvisioningEffects,
    ) = SyncIdpGroupsUseCase(shared, dependencies.grants, dependencies.directory, dependencies.matrix, effects)
}

/** TPL-18, continued: the use cases of break-glass (INV-REQ-896, INV-REQ-897, INV-REQ-1269). */
@Configuration(proxyBeanMethods = false)
class BreakGlassUseCases {
    @Bean
    fun breakGlassSessions(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): BreakGlassSessions = JooqBreakGlassSessions(dsl, tenants, clock)

    @Bean
    fun breakGlassAlerts(
        outbox: OutboxWriter,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): BreakGlassAlerts = BreakGlassAlertOutbox(outbox, tenants, mapper)

    /** VP-08 as `setPolicy` of service-runtime stores it and the config-resolver resolves it (INV-REQ-1269). */
    @Bean
    fun breakGlassSettings(
        reader: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): BreakGlassSettings = VariationPointBreakGlassSettings(reader, tenants, clock, mapper)

    @Bean
    fun breakGlassEffects(
        alerts: BreakGlassAlerts,
        audit: GrantAudit,
    ) = BreakGlassEffects(alerts, audit)

    @Bean
    fun unlockBreakGlassUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        settings: BreakGlassSettings,
        effects: BreakGlassEffects,
        clock: Clock,
    ) = UnlockBreakGlassUseCase(dependencies.tx, sessions, dependencies.grants, settings, effects, clock)

    @Bean
    fun recordBreakGlassActionUseCase(
        dependencies: GrantDependencies,
        sessions: BreakGlassSessions,
        effects: BreakGlassEffects,
        clock: Clock,
    ) = RecordBreakGlassActionUseCase(dependencies.tx, sessions, effects, clock)
}
