package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.certification.ConsumedCertificationEvents
import com.dynokore.gst.tenancy.adapters.certification.IdentityTripleChangedHandler
import com.dynokore.gst.tenancy.adapters.certification.RulePackActivatedCertificationHandler
import com.dynokore.gst.tenancy.adapters.events.CertificationStatusChangedOutbox
import com.dynokore.gst.tenancy.adapters.persistence.JooqCertificationStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqCertifiedEntityDirectory
import com.dynokore.gst.tenancy.application.certification.CertifiableEntities
import com.dynokore.gst.tenancy.application.certification.CertificationStatusPublisher
import com.dynokore.gst.tenancy.application.certification.CertificationStore
import com.dynokore.gst.tenancy.application.certification.CertifiedEntityDirectory
import com.dynokore.gst.tenancy.application.certification.CertifySandbox
import com.dynokore.gst.tenancy.application.certification.MarkAllStaleForNewTrain
import com.dynokore.gst.tenancy.application.certification.MarkCertificationStale
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TPL-18: the sandbox certification use cases of INV-T-16408 constructed over the boundary adapters (INV-T-16409): the
 * store, the directory, the outbox, the two subscriptions added for the re-certification triggers and the NEW_TRAIN
 * hook. The mark of ISSUANCE_MODE is [com.dynokore.gst.tenancy.adapters.certification.IssuanceModeCertificationMark],
 * wrapped around the profile publisher in [StatusUseCases].
 */
@Configuration(proxyBeanMethods = false)
class CertificationUseCases {
    /** One bean for both ports (the store and the entity check), so each port type resolves to one candidate. */
    @Bean
    fun jooqCertificationStore(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): JooqCertificationStore = JooqCertificationStore(dsl, tenants) { now(clock) }

    @Bean
    fun certifiedEntityDirectory(
        dsl: DSLContext,
        tenants: TenantContext,
        tx: TransactionRunner,
    ): CertifiedEntityDirectory =
        JooqCertifiedEntityDirectory(dsl, tenants, tenants as PlatformTenantBinding, tx, ACTOR)

    @Bean
    fun certificationStatusPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): CertificationStatusPublisher = CertificationStatusChangedOutbox(outbox, mapper, tenants) { now(clock) }

    @Bean
    @Suppress("LongParameterList") // one provider per collaborator of the use case
    fun certifySandbox(
        tx: TransactionRunner,
        entities: CertifiableEntities,
        store: CertificationStore,
        authority: ActionAuthority,
        publisher: CertificationStatusPublisher,
        clock: ObjectProvider<Clock>,
    ): CertifySandbox = CertifySandbox(tx, entities, store, authority, publisher) { now(clock) }

    @Bean
    fun markCertificationStale(
        tx: TransactionRunner,
        store: CertificationStore,
        publisher: CertificationStatusPublisher,
    ): MarkCertificationStale = MarkCertificationStale(tx, store, publisher)

    /** The NEW_TRAIN mark of every certified entity, called when the stored `releaseTrain` fact changes. */
    @Bean
    fun markAllStaleForNewTrain(
        directory: CertifiedEntityDirectory,
        tenants: TenantContext,
        tx: TransactionRunner,
        mark: MarkCertificationStale,
    ): MarkAllStaleForNewTrain = MarkAllStaleForNewTrain(directory, tenants, tx, mark, ACTOR)

    /** RulePackActivated: NEW_PACK, and SCHEME_HANDLER by the same mark (INV-Q-16401 A). */
    @Bean
    @Suppress("LongParameterList") // one provider per collaborator of the handler
    fun certificationRulePackActivatedConsumer(
        directory: CertifiedEntityDirectory,
        tenants: TenantContext,
        tx: TransactionRunner,
        mark: MarkCertificationStale,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            ConsumedCertificationEvents.RULE_PACK_ACTIVATED_CONSUMER,
            ConsumedCertificationEvents.RULE_PACK_ACTIVATED_TOPIC,
            RulePackActivatedCertificationHandler(directory, tenants, tx, mark, mapper, ACTOR),
        )

    @Bean
    fun certificationIdentityTripleChangedConsumer(
        mark: MarkCertificationStale,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            ConsumedCertificationEvents.IDENTITY_TRIPLE_CHANGED_CONSUMER,
            ConsumedCertificationEvents.IDENTITY_TRIPLE_CHANGED_TOPIC,
            IdentityTripleChangedHandler(mark, mapper),
        )

    private companion object {
        const val ACTOR = "svc:tenancy-svc"

        /** The store keeps microseconds (timestamptz), so the instant is cut to them: the response equals the row. */
        fun now(clock: ObjectProvider<Clock>): Instant =
            clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS)
    }
}
