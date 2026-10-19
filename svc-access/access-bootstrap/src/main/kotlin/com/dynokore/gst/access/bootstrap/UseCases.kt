package com.dynokore.gst.access.bootstrap
import com.dynokore.gst.access.adapters.audit.OutboxGrantAudit
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.auditlog.AuditWriter
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
    fun grantRoleUseCase(
        dependencies: GrantDependencies,
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
    ) = GrantRoleUseCase(
        dependencies.tx,
        dependencies.grants,
        dependencies.directory,
        dependencies.matrix,
        publisher,
        audit,
    )

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
