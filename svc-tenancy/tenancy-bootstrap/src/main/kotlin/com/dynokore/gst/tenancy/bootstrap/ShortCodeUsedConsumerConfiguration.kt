package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.ShortCodeUsedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
    /** The default sink (INV-Q-11655 A, INV-Q-11657 A): a sink that the product supplies replaces it. */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

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

/**
 * The log-only [RebuildRequiredSink]: one ERROR line with the consumer, the topic and the offsets, nothing else. The
 * incident has no tenant or kind; the alert path to a person is the open platform item in tooling/followups.md.
 */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LoggerFactory
            .getLogger(LoggingRebuildRequiredSink::class.java)
            .error(
                "rebuild required: consumer {} on topic {}: next offset {} is at or below purge watermark {}",
                incident.consumer,
                incident.topic,
                incident.nextOffset,
                incident.watermark,
            )
    }
}
