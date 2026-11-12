package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPeriodRecordsChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqPeriodDueDateStore
import com.dynokore.gst.submission.adapters.persistence.JooqPeriodRecordStore
import com.dynokore.gst.submission.application.period.PeriodOfDate
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedDataReader
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedHandler
import com.dynokore.gst.submission.application.period.StoredPeriodOfDate
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
 * The consumer of `PeriodRecordsChanged` (INV-REQ-570, INV-AC-20678): the subscription, the handler over the Jackson
 * reader and the jOOQ period record store, the `PeriodOfDate` lookup over the same table, and one `JdbcInboxConsumer`
 * the event loop polls, the way [BreakGlassFeed] declares its own. The replay delivery is not wired for this topic
 * (as for `BreakGlassSessionChanged`). The consumer refuses to be built when the catalogue does not list the event
 * on exactly this topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class PeriodRecordsConsumer {
    @Bean
    fun periodRecordsChangedDataReader(): PeriodRecordsChangedDataReader = JacksonPeriodRecordsChangedDataReader()

    @Bean
    fun periodRecordStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqPeriodRecordStore = JooqPeriodRecordStore(dsl, runner)

    @Bean
    fun periodOfDate(store: JooqPeriodRecordStore): PeriodOfDate = StoredPeriodOfDate(store)

    @Bean
    fun periodRecordsChangedHandler(
        tenants: TenantContext,
        reader: PeriodRecordsChangedDataReader,
        store: JooqPeriodRecordStore,
        dsl: DSLContext,
        runner: TransactionRunner,
    ): PeriodRecordsChangedHandler =
        PeriodRecordsChangedHandler(tenants, reader, store, JooqPeriodDueDateStore(dsl, runner))

    @Bean
    @Suppress("LongParameterList")
    fun periodRecordsChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("periodRecordsChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE)
        check(entry != null && entry.topic == SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC) {
            "the event catalogue does not list ${SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE} on " +
                SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC
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
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC, SCHEMA)
    }
}
