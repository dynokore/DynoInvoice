package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessAlertOutbox
import com.dynokore.gst.access.adapters.events.AlertEntity
import com.dynokore.gst.access.adapters.events.OutboxLinkMessages
import com.dynokore.gst.access.adapters.events.ScopeAlertEntity
import com.dynokore.gst.access.adapters.link.PemSealingKeys
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.adapters.link.VariationPointLinkSettings
import com.dynokore.gst.access.adapters.persistence.JooqLinkTenants
import com.dynokore.gst.access.adapters.persistence.JooqSignatoryActions
import com.dynokore.gst.access.adapters.persistence.JooqSignedLinks
import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.approval.RecordsMappingAcknowledgements
import com.dynokore.gst.access.application.consent.ConsentEffects
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ConsentWithdrawal
import com.dynokore.gst.access.application.consent.HandoverPorts
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.application.link.LinkPasscodeSettings
import com.dynokore.gst.access.application.link.LinkPermissions
import com.dynokore.gst.access.application.link.LinkSecrets
import com.dynokore.gst.access.application.link.LinkTenants
import com.dynokore.gst.access.application.link.OaepPasscodeSealer
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.PasscodeSealer
import com.dynokore.gst.access.application.link.RecordSignatoryActionUseCase
import com.dynokore.gst.access.application.link.SealingKeys
import com.dynokore.gst.access.application.link.SecureLinkSecrets
import com.dynokore.gst.access.application.link.SignatoryActions
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.application.link.SmsGatewayBinding
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/** TPL-18. Signed links (INV-T-4940): the store, the passcode check, VP-75 and the ACC alert outbox. */
@Configuration(proxyBeanMethods = false)
class SignedLinkUseCases {
    @Bean
    fun signedLinks(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SignedLinks = JooqSignedLinks(dsl, tenants, clock)

    @Bean
    fun linkTenants(
        dsl: DSLContext,
        tx: TransactionRunner,
    ): LinkTenants = JooqLinkTenants(dsl, tx)

    @Bean
    fun passcodeDigests(): PasscodeDigests = Sha256PasscodeDigests()

    /** VP-75 as `setPolicy` of service-runtime stores it and the config-resolver resolves it (INV-REQ-2758). */
    @Bean
    fun linkPasscodeSettings(
        reader: PolicyReader,
        clock: Clock,
        mapper: ObjectMapper,
    ): LinkPasscodeSettings = VariationPointLinkSettings(reader, clock, mapper)

    /**
     * The property `access.signed-link.sms-gateway-bound` as a boolean (ruling INV-Q-17203 A): absent is false, and a
     * value other than exactly `true` or `false` stops the start-up. Read from the raw text, because the binder's
     * boolean conversion would accept `yes`, `on` and `1`.
     */
    @Bean
    fun smsGatewayBinding(environment: Environment): SmsGatewayBinding {
        val bound =
            when (val raw = environment.getProperty(SMS_GATEWAY_BOUND)) {
                null -> false
                "true" -> true
                "false" -> false
                else -> error("$SMS_GATEWAY_BOUND must be true or false, not '$raw'")
            }
        return SmsGatewayBinding { bound }
    }

    /**
     * The public delivery key, read once here at start-up from the SecretStore binding; a key that is absent,
     * unreadable, not RSA or shorter than 3072 bits stops the start-up (fail closed). `lazy-initialization` is false.
     */
    @Bean
    fun sealingKeys(secrets: SecretStore): SealingKeys =
        PemSealingKeys.load { secrets.read(SecretRef(PemSealingKeys.SECRET_REF)).reveal() }

    @Bean
    fun passcodeSealer(keys: SealingKeys): PasscodeSealer = OaepPasscodeSealer(keys)

    @Bean
    fun linkSecrets(): LinkSecrets = SecureLinkSecrets()

    @Bean
    fun outboxLinkMessages(
        outbox: OutboxWriter,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ) = OutboxLinkMessages(outbox, tenants, mapper)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun issueSignedLinkUseCase(
        tx: TransactionRunner,
        links: SignedLinks,
        secrets: LinkSecrets,
        digests: PasscodeDigests,
        sealer: PasscodeSealer,
        settings: LinkPasscodeSettings,
        smsGateway: SmsGatewayBinding,
        messages: OutboxLinkMessages,
        audit: GrantAudit,
        clock: Clock,
    ) = IssueSignedLinkUseCase(
        tx,
        links,
        secrets,
        digests,
        sealer,
        settings,
        smsGateway,
        messages,
        audit,
        SYSTEM_ACTOR,
        clock,
    )

    @Bean
    fun signatoryActions(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SignatoryActions = JooqSignatoryActions(dsl, tenants, clock)

    @Bean
    fun linkPermissions(
        matrix: PermissionMatrix,
        directory: EntityDirectory,
    ) = LinkPermissions(matrix, directory)

    @Bean
    fun consentWithdrawal(
        consents: ConsentRepository,
        handover: HandoverPorts,
        effects: ConsentEffects,
        clock: Clock,
    ) = ConsentWithdrawal(consents, handover, effects, clock)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun recordSignatoryActionUseCase(
        tx: TransactionRunner,
        links: SignedLinks,
        permissions: LinkPermissions,
        actions: SignatoryActions,
        consents: ConsentRepository,
        withdrawal: ConsentWithdrawal,
        acknowledgements: RecordsMappingAcknowledgements,
        audit: GrantAudit,
        clock: Clock,
    ) = RecordSignatoryActionUseCase(
        tx,
        links,
        permissions,
        actions,
        consents,
        withdrawal,
        acknowledgements,
        audit,
        clock,
    )

    @Bean
    fun alertEntity(): AlertEntity = ScopeAlertEntity()

    /** ACC-001, ACC-003 and ACC-004 as the contract event AccessAlertRaised, in the command transaction. */
    @Bean
    fun accessAlerts(
        outbox: OutboxWriter,
        tenants: TenantContext,
        mapper: ObjectMapper,
        entity: AlertEntity,
    ): AccessAlerts = AccessAlertOutbox(outbox, tenants, mapper, entity)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun verifySignatoryLinkUseCase(
        tx: TransactionRunner,
        links: SignedLinks,
        digests: PasscodeDigests,
        settings: LinkPasscodeSettings,
        alerts: AccessAlerts,
        clock: Clock,
    ) = VerifySignatoryLinkUseCase(tx, links, digests, settings, alerts, clock)

    companion object {
        const val SMS_GATEWAY_BOUND = "access.signed-link.sms-gateway-bound"

        /** The sender of an event-issued link and the actor of its audit entry (ruling INV-Q-17207 A). */
        const val SYSTEM_ACTOR = "system:access-svc"
    }
}
