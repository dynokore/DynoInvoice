package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPackActivatedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonPackStagedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqPackMarginStore
import com.dynokore.gst.submission.application.pack.CalendarActivationReader
import com.dynokore.gst.submission.application.pack.CalendarPackStagedMarginHandler
import com.dynokore.gst.submission.application.pack.PackActivatedDataReader
import com.dynokore.gst.submission.application.pack.PackActivatedHandler
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.pack.PackMarginStore
import com.dynokore.gst.submission.application.pack.PackStagedDataReader
import com.dynokore.gst.submission.application.pack.StoredPackDefaultMargin
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
 * The local pack margin store (INV-REQ-554, event-carried state transfer): the store over the two calendar pack
 * tables, the handlers of `RulePackStaged` and `RulePackActivated`, the `PackDefaultMargin` the drain reads, and one
 * inbox consumer per topic. Each consumer refuses to be built when the catalogue does not list its event type on
 * exactly its topic (fail closed). The replay delivery is not wired for these topics, as for
 * `BreakGlassSessionChanged`.
 */
@Configuration(proxyBeanMethods = false)
class PackMarginConsumers {
    @Bean
    fun packMarginStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqPackMarginStore = JooqPackMarginStore(dsl, runner)

    @Bean
    fun packStagedDataReader(): PackStagedDataReader = JacksonPackStagedDataReader()

    @Bean
    fun packActivatedDataReader(): PackActivatedDataReader = JacksonPackActivatedDataReader()

    @Bean
    fun calendarPackStagedMarginHandler(
        tenants: TenantContext,
        reader: PackStagedDataReader,
        store: PackMarginStore,
    ): CalendarPackStagedMarginHandler = CalendarPackStagedMarginHandler(tenants, reader, store)

    @Bean
    fun packActivatedHandler(
        tenants: TenantContext,
        reader: PackActivatedDataReader,
        store: PackMarginStore,
    ): PackActivatedHandler = PackActivatedHandler(tenants, reader, store)

    @Bean
    fun packDefaultMargin(reader: CalendarActivationReader): PackDefaultMargin = StoredPackDefaultMargin(reader)

    @Bean
    @Suppress("LongParameterList")
    fun rulePackStagedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("calendarPackStagedMarginHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            rulePackStagedSubscription(),
            SubmissionEvents.RULE_PACK_STAGED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun rulePackActivatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("packActivatedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            rulePackActivatedSubscription(),
            SubmissionEvents.RULE_PACK_ACTIVATED_TYPE,
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

        fun rulePackStagedSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.RULE_PACK_STAGED_TOPIC, SCHEMA)

        fun rulePackActivatedSubscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.RULE_PACK_ACTIVATED_TOPIC, SCHEMA)
    }
}
