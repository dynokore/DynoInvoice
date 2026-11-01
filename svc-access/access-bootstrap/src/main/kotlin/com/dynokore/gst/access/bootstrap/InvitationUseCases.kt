package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.FirstAdministratorAcceptance
import com.dynokore.gst.access.adapters.http.FirstAdministratorEndpoints
import com.dynokore.gst.access.adapters.http.FirstAdministratorProvisioning
import com.dynokore.gst.access.adapters.invitation.HmacInvitationCodes
import com.dynokore.gst.access.adapters.invitation.RandomInvitationRefs
import com.dynokore.gst.access.adapters.persistence.JooqFirstAdministratorInvitations
import com.dynokore.gst.access.adapters.persistence.JooqInvitationTenantLookup
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.invitation.AcceptFirstAdministratorEffects
import com.dynokore.gst.access.application.invitation.AcceptFirstAdministratorInvitation
import com.dynokore.gst.access.application.invitation.FirstAdministratorEffects
import com.dynokore.gst.access.application.invitation.FirstAdministratorInvitations
import com.dynokore.gst.access.application.invitation.InvitationCodes
import com.dynokore.gst.access.application.invitation.InvitationRefs
import com.dynokore.gst.access.application.invitation.InvitationSettings
import com.dynokore.gst.access.application.invitation.InvitationTenantLookup
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministrator
import com.dynokore.gst.access.application.invitation.ReissueFirstAdministratorInvitation
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun firstAdministratorProvisioning(
        useCase: ProvisionFirstAdministrator,
        reissueUseCase: ReissueFirstAdministratorInvitation,
        tenants: TenantContext,
        request: HttpServletRequest,
        serviceCaller: ServiceCaller,
    ) = FirstAdministratorProvisioning(useCase, reissueUseCase, tenants, request, serviceCaller)

    @Bean
    fun invitationTenantLookup(
        dsl: DSLContext,
        tx: TransactionRunner,
    ): InvitationTenantLookup = JooqInvitationTenantLookup(dsl, tx)

    @Bean
    fun firstAdministratorEndpoints(
        provisioning: FirstAdministratorProvisioning,
        acceptance: FirstAdministratorAcceptance,
    ) = FirstAdministratorEndpoints(provisioning, acceptance)

    @Bean
    fun firstAdministratorAcceptance(
        useCase: AcceptFirstAdministratorInvitation,
        lookup: InvitationTenantLookup,
        platform: PlatformTenantBinding,
        tenants: TenantContext,
        request: HttpServletRequest,
    ) = FirstAdministratorAcceptance(useCase, lookup, platform, tenants, request)

    @Bean
    fun acceptFirstAdministratorEffects(
        invitations: FirstAdministratorInvitations,
        codes: InvitationCodes,
        publisher: AuthorityChangePublisher,
        audit: GrantAudit,
    ) = AcceptFirstAdministratorEffects(invitations, codes, publisher, audit)

    @Bean
    fun acceptFirstAdministratorInvitation(
        dependencies: GrantDependencies,
        effects: AcceptFirstAdministratorEffects,
        clock: Clock,
    ) = AcceptFirstAdministratorInvitation(
        dependencies.tx,
        dependencies.grants,
        effects,
        dependencies.directory,
        dependencies.matrix,
        clock,
    )

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

    @Bean
    fun reissueFirstAdministratorInvitation(
        tx: TransactionRunner,
        effects: FirstAdministratorEffects,
        clock: Clock,
        properties: InvitationProperties,
    ) = ReissueFirstAdministratorInvitation(tx, effects, clock, InvitationSettings(properties.codeKeyVersion))
}
