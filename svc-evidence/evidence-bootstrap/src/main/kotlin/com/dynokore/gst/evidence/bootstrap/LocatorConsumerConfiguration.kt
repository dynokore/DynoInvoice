package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
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
 * The subscription of evidence-svc to `CanonicalVersionCreated` (INV-Q-7213 A (1)), as one inbox consumer of its own
 * (product-runtime declares the consumers of its feeds, so the default one of service-runtime backs off). It works on
 * the inbox, offset and dead-letter tables of schema `evidence` (V3__evidence_consumer_side.sql); its handler runs in
 * the consumer's transaction on the tenant-bound [DSLContext], so the locator, the drained records, the inbox row and
 * the offset commit together (INV-REQ-1222). The service binds no [RebuildRequiredSink] yet (reported gap): without
 * one the context does not start, which keeps the missing incident path visible.
 */
@Configuration(proxyBeanMethods = false)
class LocatorConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun locatorConsumer(
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
            ChainWiring.locatorHandler(dsl, clock),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "evidence-svc"
        const val TOPIC = "gst.canonical.canonical-version-created.v1"
        const val SCHEMA = ChainWiring.SCHEMA
    }
}
