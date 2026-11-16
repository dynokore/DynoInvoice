package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.OffboardingExportFinishedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.JooqOffboardingStore
import com.dynokore.gst.tenancy.application.offboarding.CompleteOffboardingExport
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of tenancy-svc to `OffboardingExportFinished` (INV-REQ-877, INV-AC-22961), as one inbox consumer of
 * its own on the inbox, offset and dead-letter tables of schema `tenancy`. The handler moves the REQUESTED record to
 * COMPLETED or FAILED in the consumer's transaction, so the record, the inbox row and the offset commit together; a
 * payload that breaks the contract goes to the dead letter. The default [RebuildRequiredSink] comes from
 * [ShortCodeUsedConsumerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class OffboardingExportFinishedConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun offboardingExportFinishedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        mapper: ObjectMapper,
        complete: CompleteOffboardingExport,
        store: JooqOffboardingStore,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            OffboardingExportFinishedHandler(complete, mapper, store),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = TenancyEventCatalogue.SERVICE
        const val TOPIC = TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TOPIC
        const val SCHEMA = "tenancy"
    }
}
