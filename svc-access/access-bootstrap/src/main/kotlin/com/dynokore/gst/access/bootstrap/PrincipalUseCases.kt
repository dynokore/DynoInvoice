package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.credential.NimbusCredentialMaterial
import com.dynokore.gst.access.adapters.credential.TightenOnlyClientSecretWriter
import com.dynokore.gst.access.adapters.credential.VariationPointClientSecretPolicy
import com.dynokore.gst.access.adapters.events.PrincipalRevocationOutbox
import com.dynokore.gst.access.adapters.persistence.JooqCredentialRepository
import com.dynokore.gst.access.adapters.persistence.JooqPrincipalRepository
import com.dynokore.gst.access.adapters.persistence.JooqRevocationListWriter
import com.dynokore.gst.access.application.authorise.MachineAuthorise
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.principal.AuthenticatePrincipalUseCase
import com.dynokore.gst.access.application.principal.ClientSecretPolicy
import com.dynokore.gst.access.application.principal.CreatePrincipalUseCase
import com.dynokore.gst.access.application.principal.CredentialIssuer
import com.dynokore.gst.access.application.principal.CredentialMaterial
import com.dynokore.gst.access.application.principal.CredentialRepository
import com.dynokore.gst.access.application.principal.PrincipalRepository
import com.dynokore.gst.access.application.principal.PrincipalRevocationPublisher
import com.dynokore.gst.access.application.principal.RevocationListWriter
import com.dynokore.gst.access.application.principal.RevokePrincipalUseCase
import com.dynokore.gst.access.application.principal.RotatePrincipalUseCase
import com.dynokore.gst.access.application.principal.WithdrawCredentialUseCase
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** TPL-18: the service principals' use cases (INV-T-4937), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class PrincipalUseCases {
    @Bean
    fun principalRepository(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): PrincipalRepository = JooqPrincipalRepository(dsl, tenants, clock)

    @Bean
    fun principalRevocationPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        clock: Clock,
        directory: EntityDirectory,
    ): PrincipalRevocationPublisher = PrincipalRevocationOutbox(outbox, mapper, clock, directory)

    /** The list entry is written in a transaction of its own, bound to the platform tenant (INV-Q-8785 A). */
    @Bean
    fun revocationListWriter(
        dsl: DSLContext,
        tenants: PlatformTenantBinding,
        tx: TransactionRunner,
        environment: Environment,
        clock: Clock,
    ): RevocationListWriter =
        JooqRevocationListWriter(
            dsl,
            tenants,
            tx,
            WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value,
            clock,
        )

    @Bean
    fun createPrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        issuer: CredentialIssuer,
        audit: GrantAudit,
    ) = CreatePrincipalUseCase(tx, principals, issuer, audit)

    @Bean
    fun revokePrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        publisher: PrincipalRevocationPublisher,
        revocationList: RevocationListWriter,
        audit: GrantAudit,
    ) = RevokePrincipalUseCase(tx, principals, publisher, revocationList, audit)
}
