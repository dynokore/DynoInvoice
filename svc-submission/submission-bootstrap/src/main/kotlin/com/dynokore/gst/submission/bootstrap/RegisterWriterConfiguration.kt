package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonCanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonPeriodDueDateChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqPeriodDueDateStore
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedHandler
import com.dynokore.gst.submission.application.register.DocumentKindResolver
import com.dynokore.gst.submission.application.register.DocumentTypeKindResolver
import com.dynokore.gst.submission.application.register.FilingDeadlineSource
import com.dynokore.gst.submission.application.register.PeriodDueDateChangedDataReader
import com.dynokore.gst.submission.application.register.PeriodDueDateChangedHandler
import com.dynokore.gst.submission.application.register.PeriodDueDateDeadlineSource
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.RegisterEntryStore
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
 * Registers the register writer in the running service (INV-REQ-570, INV-REQ-975): the handlers of
 * `CanonicalVersionCreated` and `PeriodDueDateChanged` with the production store and ports, and one inbox consumer
 * for each topic, declared the way [ExtensionConsumerConfiguration] does. service-runtime's event loop drives every
 * `InboxConsumer` bean. Each consumer refuses to be built when the catalogue does not list its event type on exactly
 * its topic (fail closed). The subscriptions are not beans: [ConsumerConfiguration.payloadReadyConsumer] takes the one
 * `ConsumerSubscription` bean by type.
 */
@Configuration(proxyBeanMethods = false)
class RegisterWriterConfiguration {
    @Bean
    fun registerEntryStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): RegisterEntryStore = JooqRegisterEntryStore(dsl, runner)

    @Bean
    fun periodDueDates(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): PeriodDueDates = JooqPeriodDueDateStore(dsl, runner)

    @Bean
    fun canonicalVersionCreatedDataReader(): CanonicalVersionCreatedDataReader =
        JacksonCanonicalVersionCreatedDataReader()

    @Bean
    fun periodDueDateChangedDataReader(): PeriodDueDateChangedDataReader = JacksonPeriodDueDateChangedDataReader()

    @Bean
    fun documentKindResolver(): DocumentKindResolver = DocumentTypeKindResolver()

    @Bean
    fun filingDeadlineSource(dueDates: PeriodDueDates): FilingDeadlineSource = PeriodDueDateDeadlineSource(dueDates)

    @Bean
    @Suppress("LongParameterList")
    fun canonicalVersionCreatedHandler(
        tenants: TenantContext,
        reader: CanonicalVersionCreatedDataReader,
        deadlines: FilingDeadlineSource,
        kinds: DocumentKindResolver,
        store: RegisterEntryStore,
    ): CanonicalVersionCreatedHandler = CanonicalVersionCreatedHandler(tenants, reader, deadlines, kinds, store)

    @Bean
    fun periodDueDateChangedHandler(
        tenants: TenantContext,
        reader: PeriodDueDateChangedDataReader,
        dueDates: PeriodDueDates,
    ): PeriodDueDateChangedHandler = PeriodDueDateChangedHandler(tenants, reader, dueDates)

    @Bean
    @Suppress("LongParameterList")
    fun canonicalVersionCreatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("canonicalVersionCreatedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            canonicalVersionCreatedSubscription(),
            SubmissionEvents.CANONICAL_VERSION_CREATED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun periodDueDateChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("periodDueDateChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            periodDueDateChangedSubscription(),
            SubmissionEvents.PERIOD_DUE_DATE_CHANGED_TYPE,
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
        eventType: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val entry = catalogue.find(eventType)
        check(entry != null && entry.topic == subscription.topic) {
            "the event catalogue does not list $eventType on ${subscription.topic}"
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

        fun canonicalVersionCreatedSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.CANONICAL_VERSION_CREATED_TOPIC, SCHEMA)

        fun periodDueDateChangedSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.PERIOD_DUE_DATE_CHANGED_TOPIC, SCHEMA)
    }
}
