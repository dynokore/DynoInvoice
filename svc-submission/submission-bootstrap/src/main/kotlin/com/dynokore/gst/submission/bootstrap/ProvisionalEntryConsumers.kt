package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonDocumentHeldDataReader
import com.dynokore.gst.submission.adapters.events.JacksonDocumentReleasedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqProvisionalEntryStore
import com.dynokore.gst.submission.application.period.PeriodOfDate
import com.dynokore.gst.submission.application.register.DateElectionSource
import com.dynokore.gst.submission.application.register.DocumentHeldDataReader
import com.dynokore.gst.submission.application.register.DocumentHeldHandler
import com.dynokore.gst.submission.application.register.DocumentReleasedDataReader
import com.dynokore.gst.submission.application.register.DocumentReleasedHandler
import com.dynokore.gst.submission.application.register.ProvisionalEntryStore
import com.dynokore.gst.submission.domain.register.DateElection
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The consumers of `DocumentHeld` and `DocumentReleased` (INV-REQ-570 behavior 2, INV-AC-1778, INV-AC-1691): the two
 * Jackson readers, the jOOQ provisional entry store, the two handlers and one `JdbcInboxConsumer` per topic, declared
 * the way [PeriodRecordsConsumer] does. The replay delivery is not wired for these topics (as for
 * `BreakGlassSessionChanged`). [DateElectionSource] answers `DateElection.Unknown` until the production source of the
 * entity's date election is decided (answer of the design question of INV-AC-1778, case 4). Each consumer refuses to
 * be built when the catalogue does not list its event type on exactly its topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class ProvisionalEntryConsumers {
    @Bean
    fun documentHeldDataReader(): DocumentHeldDataReader = JacksonDocumentHeldDataReader()

    @Bean
    fun documentReleasedDataReader(): DocumentReleasedDataReader = JacksonDocumentReleasedDataReader()

    @Bean
    fun provisionalEntryStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ProvisionalEntryStore = JooqProvisionalEntryStore(dsl, runner)

    @Bean
    fun dateElectionSource(): DateElectionSource = DateElectionSource { _, _ -> DateElection.Unknown }

    @Bean
    @Suppress("LongParameterList")
    fun documentHeldHandler(
        tenants: TenantContext,
        reader: DocumentHeldDataReader,
        periods: PeriodOfDate,
        elections: DateElectionSource,
        store: ProvisionalEntryStore,
        clock: Clock,
    ): DocumentHeldHandler = DocumentHeldHandler(tenants, reader, periods, elections, store, clock)

    @Bean
    fun documentReleasedHandler(
        tenants: TenantContext,
        reader: DocumentReleasedDataReader,
        store: ProvisionalEntryStore,
    ): DocumentReleasedHandler = DocumentReleasedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun documentHeldConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("documentHeldHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            heldSubscription(),
            SubmissionEvents.DOCUMENT_HELD_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun documentReleasedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("documentReleasedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            releasedSubscription(),
            SubmissionEvents.DOCUMENT_RELEASED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Suppress("LongParameterList")
    private fun consumer(
        subscription: ConsumerSubscription,
        type: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val entry = catalogue.find(type)
        check(entry != null && entry.topic == subscription.topic) {
            "the event catalogue does not list $type on ${subscription.topic}"
        }
        return JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )
    }

    companion object {
        private const val CONSUMER = "submission-svc"
        private const val SCHEMA = "submission"

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun heldSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.DOCUMENT_HELD_TOPIC, SCHEMA)

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun releasedSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.DOCUMENT_RELEASED_TOPIC, SCHEMA)
    }
}
