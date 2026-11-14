package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.duedates.JacksonPeriodRecordsChangedReader
import com.dynokore.gst.consoleBff.adapters.duedates.JooqPeriodDueDateStore
import com.dynokore.gst.consoleBff.application.duedates.PeriodDueDateStore
import com.dynokore.gst.consoleBff.application.duedates.PeriodRecordsChangedHandler
import com.dynokore.gst.consoleBff.application.duedates.PeriodRecordsChangedReader
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
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The inbox consumer of `PeriodRecordsChanged` (INV-AC-21079): one `JdbcInboxConsumer` on
 * `gst.tenancy.period-records-changed.v1` in schema `console_bff`, over the handler of INV-T-21080 and the due-date
 * store. The first delivery of a period's `filingDueDate` is stored as version 0 (event-carried state transfer); the
 * inbox makes a duplicate delivery a no-op and a malformed payload a dead letter.
 */
@Configuration(proxyBeanMethods = false)
class PeriodRecordsConsumerConfiguration {
    @Bean
    fun periodRecordsSubscription(): ConsumerSubscription = ConsumerSubscription(CONSUMER, TOPIC, SCHEMA)

    @Bean
    fun periodDueDateStore(dsl: DSLContext): PeriodDueDateStore = JooqPeriodDueDateStore(dsl)

    @Bean
    fun periodRecordsChangedReader(): PeriodRecordsChangedReader = JacksonPeriodRecordsChangedReader()

    @Bean
    fun periodRecordsChangedHandler(
        tenants: TenantContext,
        reader: PeriodRecordsChangedReader,
        store: PeriodDueDateStore,
    ): PeriodRecordsChangedHandler = PeriodRecordsChangedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun periodRecordsConsumer(
        @Qualifier("periodRecordsSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("periodRecordsChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = ExceptionConsumerConfiguration.CONSUMER
        const val SCHEMA = ExceptionConsumerConfiguration.SCHEMA
        const val TOPIC = "gst.tenancy.period-records-changed.v1"
    }
}
