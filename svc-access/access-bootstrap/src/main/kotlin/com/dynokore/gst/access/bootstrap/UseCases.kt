package com.dynokore.gst.access.bootstrap
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.adapters.audit.OutboxGrantAudit
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.events.StoredAuthorityVersions
import com.dynokore.gst.access.adapters.events.VersionStoringGrantChanges
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.landing.VariationPointRolePrecedence
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqLandingChoices
import com.dynokore.gst.access.adapters.persistence.JooqSoleOperatorAcks
import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.AuthorisePlatform
import com.dynokore.gst.access.application.authorise.OperatorOverlay
import com.dynokore.gst.access.application.authorise.PlatformRead
import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleEffects
import com.dynokore.gst.access.application.grant.GrantRoleRules
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.landing.LandingChoices
import com.dynokore.gst.access.application.landing.LandingScreens
import com.dynokore.gst.access.application.landing.RolePrecedence
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.application.separation.RecordSoleOperatorUseCase
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.SoleOperatorScopePolicy
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorUseCase
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18: the only place a use case is constructed. No DataSource bean here or anywhere in the service (owner ruling
 * INV-Q-4196): the one application pool is Spring Boot's `dataSource` from spring.datasource.* (TPL-16).
 */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun grantRepository(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        consent: ConsentCheck,
    ): GrantRepository = JooqGrantRepository(dsl, tenants, clock, consent)

    @Bean
    fun entityDirectory(
        structure: StructureReplica,
        tenants: TenantContext,
    ): EntityDirectory = ReplicaEntityDirectory(structure, tenants)

    /** What every grant use case shares: the transaction, the live grants, the structure and the permission matrix. */
    @Bean
    fun grantDependencies(
        tx: TransactionRunner,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
    ) = GrantDependencies(tx, grants, directory, matrix)

    @Bean
    fun authorityChangePublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        dsl: DSLContext,
        head: AuthorityHeadStore,
        incarnations: AuthorityIncarnationStore,
    ): AuthorityChangePublisher =
        VersionStoringGrantChanges(AuthorityChangedOutbox(outbox, mapper, head, incarnations), StoredAuthorityVersions(dsl))

    @Bean
    fun grantAudit(
        audit: AuditWriter,
        tenants: TenantContext,
    ): GrantAudit = OutboxGrantAudit(audit, tenants)

    @Bean
    fun authorise(
        dependencies: GrantDependencies,
        agentScope: AgentScope,
        clock: Clock,
        operators: OperatorOverlay,
    ) = Authorise(
        dependencies.tx,
        dependencies.grants,
        dependencies.directory,
        dependencies.matrix,
        agentScope,
        clock,
        operators,
    )

    @Bean
    fun authorisePlatform(
        platform: PlatformRead,
        dependencies: GrantDependencies,
        clock: Clock,
        operators: OperatorOverlay,
    ) = AuthorisePlatform(platform, dependencies.grants, dependencies.matrix, clock, operators)

    @Bean
    fun grantRoleEffects(
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
    ) = GrantRoleEffects(publisher, audit)

    @Bean
    fun grantRoleRules(
        catalogue: RoleCatalogue,
        soleOperators: SoleOperatorAcks,
        soleOperatorScope: SoleOperatorScopePolicy,
        conflictingPairs: ConflictingRolePairs,
    ) = GrantRoleRules(catalogue, soleOperators, soleOperatorScope, conflictingPairs)

    @Bean
    fun grantRoleUseCase(
        dependencies: GrantDependencies,
        effects: GrantRoleEffects,
        rules: GrantRoleRules,
    ) = GrantRoleUseCase(
        dependencies.tx,
        dependencies.grants,
        effects,
        rules,
    )

    @Bean
    fun revokeGrantUseCase(
        dependencies: GrantDependencies,
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
    ) = RevokeGrantUseCase(
        dependencies.tx,
        dependencies.grants,
        publisher,
        audit,
    )
}

/** The collaborators every grant use case is built from, so that no bean method takes more than a few parameters. */
class GrantDependencies(
    val tx: TransactionRunner,
    val grants: GrantRepository,
    val directory: EntityDirectory,
    val matrix: PermissionMatrix,
)
