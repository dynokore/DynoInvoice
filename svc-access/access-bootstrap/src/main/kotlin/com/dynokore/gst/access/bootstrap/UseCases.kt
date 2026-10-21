package com.dynokore.gst.access.bootstrap
import com.dynokore.gst.access.adapters.audit.OutboxGrantAudit
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.landing.VariationPointRolePrecedence
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqLandingChoices
import com.dynokore.gst.access.adapters.persistence.JooqSoleOperatorAcks
import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.landing.LandingChoices
import com.dynokore.gst.access.application.landing.LandingScreens
import com.dynokore.gst.access.application.landing.RolePrecedence
import com.dynokore.gst.access.application.separation.RecordSoleOperatorUseCase
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
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
    ): AuthorityChangePublisher = AuthorityChangedOutbox(outbox, mapper)

    @Bean
    fun grantAudit(
        audit: AuditWriter,
        tenants: TenantContext,
    ): GrantAudit = OutboxGrantAudit(audit, tenants)

    @Bean
    fun authorise(
        dependencies: GrantDependencies,
        clock: Clock,
    ) = Authorise(dependencies.tx, dependencies.grants, dependencies.directory, dependencies.matrix, clock)

    @Bean
    fun soleOperatorAcks(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SoleOperatorAcks = JooqSoleOperatorAcks(dsl, tenants, clock)

    @Bean
    fun grantRoleUseCase(
        dependencies: GrantDependencies,
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
        catalogue: RoleCatalogue,
        soleOperators: SoleOperatorAcks,
    ) = GrantRoleUseCase(
        dependencies.tx,
        dependencies.grants,
        dependencies.directory,
        dependencies.matrix,
        publisher,
        audit,
        catalogue,
        soleOperators,
    )

    @Bean
    fun recordSoleOperatorUseCase(
        tx: TransactionRunner,
        acks: SoleOperatorAcks,
        audit: GrantAudit,
    ) = RecordSoleOperatorUseCase(tx, acks, audit)

    @Bean
    fun withdrawSoleOperatorUseCase(
        tx: TransactionRunner,
        acks: SoleOperatorAcks,
        audit: GrantAudit,
    ) = WithdrawSoleOperatorUseCase(tx, acks, audit)

    @Bean
    fun landingChoices(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): LandingChoices = JooqLandingChoices(dsl, tenants, clock)

    /** VP-73 as `setPolicy` of service-runtime stores it and the config-resolver resolves it (INV-REQ-2756). */
    @Bean
    fun rolePrecedence(
        reader: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): RolePrecedence = VariationPointRolePrecedence(reader, tenants, clock, mapper)

    /** The check of VP-73 `setPolicy` asks of its owning service (INV-Q-3167); it is the only variation point checked here. */
    @Bean
    fun rolePrecedenceConstraints(
        catalogue: RoleCatalogue,
        mapper: ObjectMapper,
    ): ValueConstraints = RolePrecedenceConstraints(catalogue, mapper)

    @Bean
    fun landingScreens(
        dependencies: GrantDependencies,
        choices: LandingChoices,
        precedence: RolePrecedence,
        clock: Clock,
    ) = LandingScreens(dependencies.tx, dependencies.grants, choices, precedence, clock)

    @Bean
    fun revokeGrantUseCase(
        dependencies: GrantDependencies,
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
    ) = RevokeGrantUseCase(
        dependencies.tx,
        dependencies.grants,
        dependencies.directory,
        dependencies.matrix,
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
