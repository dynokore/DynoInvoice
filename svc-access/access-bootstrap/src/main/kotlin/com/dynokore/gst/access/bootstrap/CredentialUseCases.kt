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

/** TPL-18: the credential and authentication use cases of the service principals (INV-T-4937). */
@Configuration(proxyBeanMethods = false)
class CredentialUseCases {
    companion object {
        /**
         * VP-77 is tighten-only and access-svc owns it: every `PolicyWriter` of the context is decorated so that a
         * return to the default preset is refused once the regulated-private preset is stored (INV-AC-2770).
         */
        @Bean
        @JvmStatic
        fun tightenOnlyClientSecret(reader: ObjectProvider<PolicyReader>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is PolicyWriter && bean !is TightenOnlyClientSecretWriter) {
                        TightenOnlyClientSecretWriter(bean) { reader.getObject() }
                    } else {
                        bean
                    }
            }
    }

    @Bean
    fun credentialRepository(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): CredentialRepository = JooqCredentialRepository(dsl, tenants, clock)

    @Bean
    fun credentialMaterial(): CredentialMaterial = NimbusCredentialMaterial()

    /** VP-77 as the config-resolver resolves it at installation scope (INV-REQ-2760). */
    @Bean
    fun clientSecretPolicy(
        reader: PolicyReader,
        clock: Clock,
    ): ClientSecretPolicy = VariationPointClientSecretPolicy(reader, clock)

    @Bean
    fun credentialIssuer(
        credentials: CredentialRepository,
        material: CredentialMaterial,
        secretPolicy: ClientSecretPolicy,
    ) = CredentialIssuer(credentials, material, secretPolicy)

    @Bean
    fun rotatePrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        credentials: CredentialRepository,
        issuer: CredentialIssuer,
        audit: GrantAudit,
        clock: Clock,
    ) = RotatePrincipalUseCase(tx, principals, credentials, issuer, audit, clock)

    @Bean
    fun withdrawCredentialUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        credentials: CredentialRepository,
        audit: GrantAudit,
    ) = WithdrawCredentialUseCase(tx, principals, credentials, audit)

    @Bean
    fun authenticatePrincipalUseCase(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        credentials: CredentialRepository,
        material: CredentialMaterial,
        secretPolicy: ClientSecretPolicy,
        clock: Clock,
    ) = AuthenticatePrincipalUseCase(tx, principals, credentials, material, secretPolicy, clock)

    @Bean
    fun machineAuthorise(
        tx: TransactionRunner,
        principals: PrincipalRepository,
        directory: EntityDirectory,
        sets: MachinePermissionSets,
    ) = MachineAuthorise(tx, principals, directory, sets)
}
