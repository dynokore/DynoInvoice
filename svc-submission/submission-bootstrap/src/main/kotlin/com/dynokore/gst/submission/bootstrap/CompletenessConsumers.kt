package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonAggregateWindowChangedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonFeedHealthChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqFeedControlStateStore
import com.dynokore.gst.submission.adapters.persistence.JooqWindowPartStateStore
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedHandler
import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.FeedControlStates
import com.dynokore.gst.submission.application.completeness.FeedHealthChangedDataReader
import com.dynokore.gst.submission.application.completeness.FeedHealthChangedHandler
import com.dynokore.gst.submission.application.completeness.WindowPartStates
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
 * The consumers of `FeedHealthChanged` and `AggregateWindowChanged` (INV-REQ-792, INV-AC-22206): for each topic the
 * Jackson reader, the handler over the version-guarded jOOQ store and one `JdbcInboxConsumer` the event loop polls,
 * the way [PeriodRecordsConsumer] declares its own, and the [CompletenessRows] reader over both tables. The replay
 * delivery is not wired for these topics. Each consumer refuses to be built when the catalogue does not list the
 * event on exactly its topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class CompletenessConsumers {
    @Bean
    fun feedControlStates(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): FeedControlStates = JooqFeedControlStateStore(dsl, runner)

    @Bean
    fun windowPartStates(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): WindowPartStates = JooqWindowPartStateStore(dsl, runner)

    @Bean
    fun completenessRows(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): CompletenessRows = JooqCompletenessRows(dsl, runner)

    @Bean
    fun feedHealthChangedDataReader(): FeedHealthChangedDataReader = JacksonFeedHealthChangedDataReader()

    @Bean
    fun aggregateWindowChangedDataReader(): AggregateWindowChangedDataReader = JacksonAggregateWindowChangedDataReader()

    @Bean
    fun feedHealthChangedHandler(
        tenants: TenantContext,
        reader: FeedHealthChangedDataReader,
        states: FeedControlStates,
    ): FeedHealthChangedHandler = FeedHealthChangedHandler(tenants, reader, states)

    @Bean
    fun aggregateWindowChangedHandler(
        tenants: TenantContext,
        reader: AggregateWindowChangedDataReader,
        states: WindowPartStates,
    ): AggregateWindowChangedHandler = AggregateWindowChangedHandler(tenants, reader, states)

    @Bean
    @Suppress("LongParameterList")
    fun feedHealthChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("feedHealthChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            feedHealthSubscription(),
            SubmissionEvents.FEED_HEALTH_CHANGED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun aggregateWindowChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("aggregateWindowChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            aggregateWindowSubscription(),
            SubmissionEvents.AGGREGATE_WINDOW_CHANGED_TYPE,
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
        fun feedHealthSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.FEED_HEALTH_CHANGED_TOPIC, SCHEMA)

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun aggregateWindowSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.AGGREGATE_WINDOW_CHANGED_TOPIC, SCHEMA)
    }
}
