package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.duedates.JacksonPeriodDueDateChangedReader
import com.dynokore.gst.consoleBff.adapters.duedates.JooqPeriodDueDateStore
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateChangedHandler
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateChangedReader
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The inbox consumer of `PeriodDueDateChanged` (INV-AC-21078): one `JdbcInboxConsumer` over the handler of INV-T-21080
 * and the store of INV-T-21081, in schema `console_bff`, with the same consumer name as the other consumers. The replay
 * of the type goes through the shared `consoleBffReplayDelivery` of [ExceptionConsumerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class DueDateConsumerConfiguration {
    @Bean
    fun periodDueDateChangedSubscription(): ConsumerSubscription =
        ConsumerSubscription(
            ExceptionConsumerConfiguration.CONSUMER,
            ConsoleEvents.PERIOD_DUE_DATE_CHANGED_TOPIC,
            ExceptionConsumerConfiguration.SCHEMA,
        )

    @Bean
    fun periodDueDateStore(dsl: DSLContext): PeriodDueDateStore = JooqPeriodDueDateStore(dsl)

    @Bean
    fun periodDueDateChangedReader(): PeriodDueDateChangedReader = JacksonPeriodDueDateChangedReader()

    @Bean
    fun periodDueDateChangedHandler(
        tenants: TenantContext,
        reader: PeriodDueDateChangedReader,
        store: PeriodDueDateStore,
    ): PeriodDueDateChangedHandler = PeriodDueDateChangedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun periodDueDateChangedConsumer(
        @Qualifier("periodDueDateChangedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("periodDueDateChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.PERIOD_DUE_DATE_CHANGED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )
}
