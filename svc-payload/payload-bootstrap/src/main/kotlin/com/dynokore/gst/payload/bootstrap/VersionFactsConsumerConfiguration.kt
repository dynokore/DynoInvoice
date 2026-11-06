package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.application.version.VersionFactsRecorder
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription of payload-svc to `CanonicalVersionCreated` (INV-Q-17166 A), as one inbox consumer of its own on the
 * inbox, offset and dead-letter tables of schema `payload` (V1). Its handler runs in the consumer's transaction on the
 * tenant-bound [DSLContext], so the fact row, the inbox row and the offset commit together.
 */
@Configuration(proxyBeanMethods = false)
class VersionFactsConsumerConfiguration {
    /** One store typed by both ports (INV-AC-17179); the caller's transaction is the store's transaction. */
    @Bean
    fun versionFactsStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqVersionFactsStore = JooqVersionFactsStore(dsl) { tenants.current().value }

    /** INV-Q-10834 A: until the product binds an incident sink, a consumer that needs a rebuild is logged at ERROR. */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

    @Bean
    @Suppress("LongParameterList")
    fun versionFactsConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        recorder: VersionFactsRecorder,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            CanonicalVersionCreatedHandler(RecordVersionFacts(recorder)),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "payload-svc"
        const val TOPIC = "gst.canonical.canonical-version-created.v1"
        const val SCHEMA = "payload"
    }
}

/** The log-only [RebuildRequiredSink]: ERROR with the consumer, the topic and the offsets, nothing else. */
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
