package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.ShortCodeUsedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription of tenancy-svc to `ShortCodeUsed` (INV-Q-11266 A, INV-AC-2779), as one inbox consumer of its own on
 * the inbox, offset and dead-letter tables of schema `tenancy` (V202610190930__create_outbox.sql). The handler runs in
 * the consumer's transaction on the tenant-bound [DSLContext], so the flag, the inbox row and the offset commit
 * together; a payload that breaks the contract goes to the dead letter.
 */
@Configuration(proxyBeanMethods = false)
class ShortCodeUsedConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun shortCodeUsedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            ShortCodeUsedHandler(dsl),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = TenancyEventCatalogue.SERVICE
        const val TOPIC = TenancyEventCatalogue.SHORT_CODE_USED_TOPIC
        const val SCHEMA = "tenancy"
    }
}
