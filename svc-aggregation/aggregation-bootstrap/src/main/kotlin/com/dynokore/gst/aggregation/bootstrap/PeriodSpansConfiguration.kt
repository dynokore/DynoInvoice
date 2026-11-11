package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.periods.ConsumedPeriodEvents
import com.dynokore.gst.aggregation.adapters.periods.JooqPeriodSpanStore
import com.dynokore.gst.aggregation.adapters.periods.PeriodRecordsChangedHandler
import com.dynokore.gst.aggregation.adapters.periods.StoredPeriodBoundaries
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18: the read model of the period spans and its feed (INV-REQ-435, INV-Q-14444 A, INV-Q-16535 A). The store
 * backs the [PeriodBoundarySource]; one inbox consumer applies `PeriodRecordsChanged` on the topic
 * `gst.tenancy.period-records-changed.v1` only. The service declares its own consumer because product-runtime also
 * supplies `EventHandler` beans, so the default single-consumer wiring backs off.
 */
@Configuration(proxyBeanMethods = false)
class PeriodSpansConfiguration {
    @Bean
    fun periodSpanStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqPeriodSpanStore = JooqPeriodSpanStore(dsl, runner, tenants)

    @Bean
    fun periodBoundarySource(store: JooqPeriodSpanStore): PeriodBoundarySource = StoredPeriodBoundaries(store)

    @Bean
    fun periodRecordsChangedHandler(
        store: JooqPeriodSpanStore,
        mapper: ObjectMapper,
    ): PeriodRecordsChangedHandler = PeriodRecordsChangedHandler(store, mapper)

    @Bean
    fun periodRecordsChangedSubscription(): ConsumerSubscription =
        ConsumerSubscription(CONSUMER, ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED_TOPIC, SCHEMA)

    @Bean
    @Suppress("LongParameterList")
    fun periodRecordsChangedConsumer(
        periodRecordsChangedSubscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        dsl: DSLContext,
        periodRecordsChangedHandler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            periodRecordsChangedSubscription,
            catalogue,
            reader,
            scope,
            dsl,
            periodRecordsChangedHandler,
            rebuildRequired,
            clock,
        )

    /** The default sink: one ERROR line; a sink that the product supplies replaces it. */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink =
        RebuildRequiredSink {
            LoggerFactory
                .getLogger(PeriodSpansConfiguration::class.java)
                .error(
                    "consumer {} must be rebuilt: topic {} next offset {} is at or below the watermark {}",
                    it.consumer,
                    it.topic,
                    it.nextOffset,
                    it.watermark,
                )
        }

    private companion object {
        const val CONSUMER = "aggregation-svc"
        const val SCHEMA = "aggregation"
    }
}
