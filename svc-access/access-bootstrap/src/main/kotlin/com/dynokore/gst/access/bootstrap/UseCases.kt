package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.audit.OutboxGrantAudit
import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.events.AuthorityChangedOutbox
import com.dynokore.gst.access.adapters.http.GatewayCaller
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.WorkloadGatewayCaller
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.JooqRevocationStore
import com.dynokore.gst.access.adapters.persistence.JooqVerificationKeyStore
import com.dynokore.gst.access.adapters.persistence.PlatformReads
import com.dynokore.gst.access.adapters.persistence.jooq.Access
import com.dynokore.gst.access.adapters.structure.ReplicaEntityDirectory
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.keys.CursorKey
import com.dynokore.gst.access.application.keys.RevocationList
import com.dynokore.gst.access.application.keys.RevocationStore
import com.dynokore.gst.access.application.keys.VerificationKeyStore
import com.dynokore.gst.access.application.keys.VerificationKeys
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.jooq.Schema
import org.springframework.beans.factory.annotation.Value
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
    fun accessSchema(): Schema = Access.ACCESS

    @Bean
    fun eventCatalogue(): EventCatalogue = AccessEventCatalogue.load()

    /**
     * Holds nothing until the permission catalogue is ruled (the names of the permissions of INV-NOTE-3 section 22.3 are
     * published nowhere): every `authorise` answers DENY and no event carries an action. Fail closed.
     */
    @Bean
    fun permissionMatrix(): PermissionMatrix = PermissionMatrix.EMPTY

    /** No grant manager is recognised until the permission catalogue is ruled (fail closed): grantRole and revokeGrant answer 403. */
    @Bean
    fun grantManagerCheck(): GrantManagerCheck = GrantManagerCheck.DENY_ALL

    /** Consent records belong to INV-T-4939; until then no consent is live, so a consent-derived grant covers nothing. */
    @Bean
    fun consentCheck(): ConsentCheck = ConsentCheck { false }

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

    @Bean
    fun authorise(
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        clock: Clock,
    ) = Authorise(grants, directory, matrix, clock)

    @Bean
    fun grantRoleUseCase(
        tx: TransactionRunner,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        audit: AuditWriter,
        tenants: TenantContext,
    ) = GrantRoleUseCase(
        tx,
        grants,
        directory,
        matrix,
        AuthorityChangedOutbox(outbox, mapper),
        OutboxGrantAudit(audit, tenants),
    )

    @Bean
    fun revokeGrantUseCase(
        tx: TransactionRunner,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        audit: AuditWriter,
        tenants: TenantContext,
    ) = RevokeGrantUseCase(
        tx,
        grants,
        directory,
        matrix,
        AuthorityChangedOutbox(outbox, mapper),
        OutboxGrantAudit(audit, tenants),
    )

    @Bean
    fun platformReads(
        tenants: TenantContext,
        tx: TransactionRunner,
        @Value("\${gst.service}") service: String,
    ) = PlatformReads(tenants, tx, WorkloadPrincipal(service).value)

    @Bean
    fun revocationStore(
        dsl: DSLContext,
        reads: PlatformReads,
    ): RevocationStore = JooqRevocationStore(dsl, reads)

    @Bean
    fun verificationKeyStore(
        dsl: DSLContext,
        reads: PlatformReads,
    ): VerificationKeyStore = JooqVerificationKeyStore(dsl, reads)

    /** The cursor integrity key is read through SecretStore at every use (STK-29): a rotated secret needs no restart. */
    @Bean
    fun cursorKey(secrets: SecretStore): CursorKey =
        CursorKey { secrets.read(SecretRef(CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8) }

    @Bean
    fun revocationList(
        store: RevocationStore,
        key: CursorKey,
        clock: Clock,
    ) = RevocationList(store, key, clock)

    @Bean
    fun verificationKeys(
        store: VerificationKeyStore,
        clock: Clock,
    ) = VerificationKeys(store, clock)

    /** The gateway's workload subject is configuration with no default: start-up fails when it is not set. */
    @Bean
    fun gatewayCaller(
        request: HttpServletRequest,
        verifier: WorkloadTokenVerifier,
        @Value("\${access.gateway.workload-subject}") subject: String,
    ): GatewayCaller = WorkloadGatewayCaller(request, verifier, subject)

    private companion object {
        const val CURSOR_KEY_SECRET = "access/revocation-cursor-key"
    }
}
