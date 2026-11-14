package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.exceptions.JacksonMasterDataResolvedReader
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionStore
import com.dynokore.gst.consoleBff.application.exceptions.MasterDataResolvedHandler
import com.dynokore.gst.consoleBff.application.exceptions.MasterDataResolvedReader
import com.dynokore.gst.consoleBff.application.exceptions.RecordDocumentReleased
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The inbox consumer of `MasterDataResolved` (INV-AC-21063): one `JdbcInboxConsumer` over the handler of INV-T-21064
 * and the store of INV-T-21029, in schema `console_bff`, with the same consumer name as the exception consumers. The
 * store and the rebuild sink are the beans of [ExceptionConsumerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class MasterDataConsumerConfiguration {
    @Bean
    fun masterDataResolvedSubscription(): ConsumerSubscription =
        ConsumerSubscription(
            ExceptionConsumerConfiguration.CONSUMER,
            MASTER_DATA_TOPIC,
            ExceptionConsumerConfiguration.SCHEMA,
        )

    @Bean
    fun masterDataResolvedReader(): MasterDataResolvedReader = JacksonMasterDataResolvedReader()

    @Bean
    fun masterDataResolvedHandler(
        tenants: TenantContext,
        reader: MasterDataResolvedReader,
        store: ExceptionStore,
    ): MasterDataResolvedHandler = MasterDataResolvedHandler(tenants, reader, store, RecordDocumentReleased(store))

    @Bean
    @Suppress("LongParameterList")
    fun masterDataResolvedConsumer(
        @Qualifier("masterDataResolvedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("masterDataResolvedHandler") handler: EventHandler,
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
        const val MASTER_DATA_TOPIC = "gst.masterdata.master-data-resolved.v1"
    }
}
