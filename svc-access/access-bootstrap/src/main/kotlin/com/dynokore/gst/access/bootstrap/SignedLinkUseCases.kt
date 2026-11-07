package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessAlertOutbox
import com.dynokore.gst.access.adapters.events.AlertEntity
import com.dynokore.gst.access.adapters.events.ScopeAlertEntity
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.adapters.link.VariationPointLinkSettings
import com.dynokore.gst.access.adapters.persistence.JooqLinkTenants
import com.dynokore.gst.access.adapters.persistence.JooqSignedLinks
import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.link.LinkPasscodeSettings
import com.dynokore.gst.access.application.link.LinkTenants
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    ): SignedLinks = JooqSignedLinks(dsl, tenants, clock, SignedLinkIssueUseCases.SYSTEM_ACTOR)

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
}
