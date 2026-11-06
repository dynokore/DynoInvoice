package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.OutboxLinkMessages
import com.dynokore.gst.access.adapters.link.PemSealingKeys
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.application.link.LinkPasscodeSettings
import com.dynokore.gst.access.application.link.LinkSecrets
import com.dynokore.gst.access.application.link.OaepPasscodeSealer
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.PasscodeSealer
import com.dynokore.gst.access.application.link.SealingKeys
import com.dynokore.gst.access.application.link.SecureLinkSecrets
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.application.link.SmsGatewayBinding
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18. Issuing a signed link on the event (INV-REQ-899): the start-up key, the SMS gateway property, the passcode
 * sealer, the messages through the outbox and the issue use case.
 */
@Configuration(proxyBeanMethods = false)
class SignedLinkIssueUseCases {
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

    companion object {
        const val SMS_GATEWAY_BOUND = "access.signed-link.sms-gateway-bound"

        /** The sender of an event-issued link and the actor of its audit entry (ruling INV-Q-17207 A). */
        const val SYSTEM_ACTOR = "system:access-svc"
    }
}
