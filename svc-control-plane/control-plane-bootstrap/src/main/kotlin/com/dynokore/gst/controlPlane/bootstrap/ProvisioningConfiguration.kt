package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.events.InvitationMessagesOutbox
import com.dynokore.gst.controlPlane.adapters.persistence.LicenceRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ProvisioningRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.RegionListJooq
import com.dynokore.gst.controlPlane.adapters.signing.CmsInvitationIssuer
import com.dynokore.gst.controlPlane.adapters.signing.CmsLicenceSigner
import com.dynokore.gst.controlPlane.adapters.signing.PackSigningSettings
import com.dynokore.gst.controlPlane.adapters.tenancy.HttpTenantCreator
import com.dynokore.gst.controlPlane.application.commercial.GetLicence
import com.dynokore.gst.controlPlane.application.commercial.GetTenantProvisioningRequest
import com.dynokore.gst.controlPlane.application.commercial.InvitationIssuer
import com.dynokore.gst.controlPlane.application.commercial.InvitationMessages
import com.dynokore.gst.controlPlane.application.commercial.IssueLicence
import com.dynokore.gst.controlPlane.application.commercial.LicenceRepository
import com.dynokore.gst.controlPlane.application.commercial.LicenceSigner
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenant
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRepository
import com.dynokore.gst.controlPlane.application.commercial.QualificationRepository
import com.dynokore.gst.controlPlane.application.commercial.RegionList
import com.dynokore.gst.controlPlane.application.commercial.TenantCreator
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.jooq.DSLContext
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.util.UUID

/** The settings of tenant provisioning, bound by prefix (a missing or non-positive value stops the start-up). */
@Configuration(proxyBeanMethods = false)
class ProvisioningSettingsConfiguration {
    @Bean
    fun tenancySettings(environment: Environment): TenancySettings =
        Binder
            .get(environment)
            .bind(TenancySettings.PREFIX, TenancySettings::class.java)
            .orElseThrow { IllegalStateException("${TenancySettings.PREFIX} is not configured") }
            .also { it.verify() }

    @Bean
    fun invitationSettings(environment: Environment): InvitationSettings =
        Binder
            .get(environment)
            .bind(InvitationSettings.PREFIX, InvitationSettings::class.java)
            .orElseThrow { IllegalStateException("${InvitationSettings.PREFIX} is not configured") }
            .also { it.verify() }
}

/**
 * Tenant provisioning and licences (INV-REQ-1306, INV-REQ-906 b1): the region list, the provisioning requests, the
 * tenancy-svc client, the signing of the licence file and of the first invitation, and the invitation message. The
 * operator role binding stays the fail-closed default until INV-T-9784 binds it (INV-Q-8687).
 */
@Configuration(proxyBeanMethods = false)
@Suppress("LongParameterList") // one bean method per collaborator of a use case (TPL-18)
class ProvisioningConfiguration {
    @Bean
    fun regionList(dsl: DSLContext): RegionList = RegionListJooq(dsl)

    @Bean
    fun provisioningRepository(
        dsl: DSLContext,
        clock: Clock,
    ): ProvisioningRepository = ProvisioningRepositoryJooq(dsl, clock)

    @Bean
    fun licenceRepository(dsl: DSLContext): LicenceRepository = LicenceRepositoryJooq(dsl)

    @Bean
    fun tenantCreator(settings: TenancySettings): TenantCreator =
        HttpTenantCreator(java.net.URI.create(checkNotNull(settings.baseUrl)), checkNotNull(settings.createTimeout))

    @Bean
    fun invitationIssuer(
        signer: DetachedSigner,
        keys: PackSigningKeys,
        settings: PackSigningSettings,
    ): InvitationIssuer = CmsInvitationIssuer(signer, keys, settings.ref)

    @Bean
    fun licenceSigner(
        signer: DetachedSigner,
        keys: PackSigningKeys,
        settings: PackSigningSettings,
    ): LicenceSigner = CmsLicenceSigner(signer, keys, settings.ref)

    @Bean
    fun invitationMessages(outbox: OutboxWriter): InvitationMessages = InvitationMessagesOutbox(outbox)

    @Bean
    fun provisionTenant(
        provisionings: ProvisioningRepository,
        regions: RegionList,
        tenants: TenantCreator,
        invitations: InvitationIssuer,
        messages: InvitationMessages,
        roles: OperatorRoles,
        clock: Clock,
        settings: InvitationSettings,
    ) = ProvisionTenant(
        provisionings,
        regions,
        tenants,
        invitations,
        messages,
        roles,
        clock,
        checkNotNull(settings.validity),
    ) {
        CommercialRef(
            "TEN-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(TENANT_REF_DIGITS)
                    .uppercase(),
        )
    }

    @Bean
    fun getTenantProvisioningRequest(provisionings: ProvisioningRepository) =
        GetTenantProvisioningRequest(provisionings)

    @Bean
    fun issueLicence(
        licences: LicenceRepository,
        qualifications: QualificationRepository,
        signer: LicenceSigner,
        roles: OperatorRoles,
        clock: Clock,
    ) = IssueLicence(licences, qualifications, signer, roles, clock) {
        CommercialRef(
            "LIC-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(LICENCE_REF_DIGITS)
                    .uppercase(),
        )
    }

    @Bean
    fun getLicence(licences: LicenceRepository) = GetLicence(licences)

    private companion object {
        const val TENANT_REF_DIGITS = 12
        const val LICENCE_REF_DIGITS = 12
    }
}

/** Where tenancy-svc is and how long the inline `createTenant` may take (`control-plane.tenancy.*`). */
data class TenancySettings(
    val baseUrl: String? = null,
    val createTimeout: java.time.Duration? = null,
) {
    fun verify() {
        check(!baseUrl.isNullOrBlank()) { "$PREFIX.base-url is not set" }
        check(createTimeout != null && !createTimeout.isNegative && !createTimeout.isZero) {
            "$PREFIX.create-timeout is not set to a positive duration"
        }
    }

    companion object {
        const val PREFIX = "control-plane.tenancy"
    }
}

/** How long the signed first invitation stays valid (`control-plane.invitation.validity`). */
data class InvitationSettings(
    val validity: java.time.Duration? = null,
) {
    fun verify() {
        check(validity != null && !validity.isNegative && !validity.isZero) {
            "$PREFIX.validity is not set to a positive duration"
        }
    }

    companion object {
        const val PREFIX = "control-plane.invitation"
    }
}
