package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.events.OffboardingExportFinishedOutbox
import com.dynokore.gst.evidence.adapters.events.OffboardingExportRequestedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqOffboardingExportJobs
import com.dynokore.gst.evidence.adapters.persistence.JooqSourceDocumentReferences
import com.dynokore.gst.evidence.adapters.persistence.JooqTenantPackageCandidates
import com.dynokore.gst.evidence.adapters.persistence.ObjectStorePackageStore
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.gst.evidence.application.export.ExportClassSources
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.gst.evidence.application.export.ExportResultPublisher
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention

/**
 * The whole-tenant export of INV-REQ-877: the consumer of `gst.tenancy.offboarding-export-requested.v1`, the use case
 * and its ports, and the publisher of `OffboardingExportFinished` through the outbox. The handler runs in the inbox
 * consumer's transaction, so the job row, the outbox row and the inbox row commit together; an unexpected failure rolls
 * all of them back. The consumer fails construction when the catalogue lacks the entry of the topic (fail closed).
 *
 * The [ExportChainIntegrity] gate is the bean of [ExportChainIntegrityConfiguration]; none is defined here, so a
 * missing bean fails the context at start-up. The object-store scheme is read without a default: a missing property
 * fails the context at start-up. The longest retention comes from the VP-43 bean, so no retention number is written
 * in code.
 */
@Configuration(proxyBeanMethods = false)
class OffboardingExportConfiguration {
    @Bean
    fun offboardingExportResultPublisher(
        outbox: OutboxWriter,
        clock: Clock,
    ): ExportResultPublisher = OffboardingExportFinishedOutbox(outbox, clock)

    @Bean
    fun offboardingExportClassSources(
        dsl: DSLContext,
        environment: Environment,
    ): ExportClassSources {
        val maxBytes =
            environment.getProperty(
                MAX_BYTES_PROPERTY,
                Long::class.javaObjectType,
                DEFAULT_MAX_PACKAGE_BYTES,
            )
        val sources =
            JooqTenantPackageCandidates(dsl, maxBytes).sources() +
                (PackageContentClass.SOURCE_DOCUMENT_REFERENCES to JooqSourceDocumentReferences(dsl, maxBytes))
        return ExportClassSources(sources)
    }

    @Bean
    fun offboardingExportPackageStore(
        store: ObjectStore,
        clock: Clock,
        digestMaxRetention: DigestMaxRetention,
        environment: Environment,
    ): ExportPackageStore =
        ObjectStorePackageStore(
            store,
            environment.getRequiredProperty(SCHEME_PROPERTY),
            clock,
        ) { digestMaxRetention.longest() }

    @Bean
    fun offboardingExportJobs(
        dsl: DSLContext,
        clock: Clock,
    ): ExportJobs = JooqOffboardingExportJobs(dsl, clock)

    @Bean
    @Suppress("LongParameterList")
    fun exportTenantEvidence(
        sources: ExportClassSources,
        jobs: ExportJobs,
        store: ExportPackageStore,
        integrity: ExportChainIntegrity,
        publisher: ExportResultPublisher,
        clock: Clock,
    ): ExportTenantEvidence = ExportTenantEvidence(sources, jobs, store, integrity, publisher, clock)

    @Bean
    fun offboardingExportRequestedHandler(useCase: ExportTenantEvidence): OffboardingExportRequestedHandler =
        OffboardingExportRequestedHandler(useCase)

    @Bean
    @Suppress("LongParameterList")
    fun offboardingExportRequestedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("offboardingExportRequestedHandler") handler: EventHandler,
    ): JdbcInboxConsumer {
        val entry = catalogue.topic(TOPIC)
        check(entry != null && entry.eventType == EVENT_TYPE) {
            "the event catalogue has no $EVENT_TYPE entry on $TOPIC"
        }
        return JdbcInboxConsumer(SUBSCRIPTION, catalogue, reader, scope, dsl, handler, rebuildRequired, clock)
    }

    companion object {
        const val TOPIC = "gst.tenancy.offboarding-export-requested.v1"
        const val EVENT_TYPE = "com.dynokore.gst.tenancy.OffboardingExportRequested.v1"
        const val PRODUCER = "tenancy-svc"
        const val SCHEME_PROPERTY = "evidence.offboarding-export.object-store-scheme"
        const val MAX_BYTES_PROPERTY = "evidence.offboarding-export.max-package-bytes"
        const val DEFAULT_MAX_PACKAGE_BYTES = 536870912L

        val SUBSCRIPTION = ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, TOPIC, ChainWiring.SCHEMA)
    }
}
