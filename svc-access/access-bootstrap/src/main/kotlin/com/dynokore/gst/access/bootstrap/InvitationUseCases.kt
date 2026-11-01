package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.FirstAdministratorProvisioning
import com.dynokore.gst.access.adapters.http.ProvisioningActor
import com.dynokore.gst.access.adapters.invitation.HmacInvitationCodes
import com.dynokore.gst.access.adapters.invitation.RandomInvitationRefs
import com.dynokore.gst.access.adapters.persistence.JooqFirstAdministratorInvitations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.invitation.FirstAdministratorEffects
import com.dynokore.gst.access.application.invitation.FirstAdministratorInvitations
import com.dynokore.gst.access.application.invitation.InvitationCodes
import com.dynokore.gst.access.application.invitation.InvitationRefs
import com.dynokore.gst.access.application.invitation.InvitationSettings
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministrator
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * The version of the key that derives the code of a new first-administrator invitation (INV-Q-14261 A). Configuration
 * with no default, so start-up fails when it is not set; the key `access/invitation-code-key/<version>` must be
 * provisioned in every environment before the first call.
 */
@ConfigurationProperties(prefix = "access.invitation")
class InvitationProperties(
    val codeKeyVersion: String,
)

/** TPL-18: the wiring of `provisionFirstAdministrator` and `provisionInstallationFirstAdministrator`. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationProperties::class)
class InvitationUseCases {
    @Bean
    fun firstAdministratorInvitations(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): FirstAdministratorInvitations = JooqFirstAdministratorInvitations(dsl, tenants, clock)

    @Bean
    fun invitationCodes(store: SecretStore): InvitationCodes = HmacInvitationCodes(store)

    @Bean
    fun invitationRefs(): InvitationRefs = RandomInvitationRefs()

    @Bean
    fun provisioningActor(environment: Environment): ProvisioningActor =
        ProvisioningActor(WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value)

    @Bean
    fun firstAdministratorProvisioning(
        useCase: ProvisionFirstAdministrator,
        tenants: TenantContext,
        request: HttpServletRequest,
        actor: ProvisioningActor,
    ) = FirstAdministratorProvisioning(useCase, tenants, request, actor)

    @Bean
    fun firstAdministratorEffects(
        invitations: FirstAdministratorInvitations,
        codes: InvitationCodes,
        refs: InvitationRefs,
        audit: GrantAudit,
    ) = FirstAdministratorEffects(invitations, codes, refs, audit)

    @Bean
    fun provisionFirstAdministrator(
        tx: TransactionRunner,
        grants: GrantRepository,
        effects: FirstAdministratorEffects,
        clock: Clock,
        properties: InvitationProperties,
    ) = ProvisionFirstAdministrator(tx, grants, effects, clock, InvitationSettings(properties.codeKeyVersion))
}
