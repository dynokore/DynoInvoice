package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.SubmissionDespatchedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
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
 * The subscription of tenancy-svc to `SubmissionDespatched` (INV-Q-16675 option A, INV-AC-16774), as one inbox consumer
 * of its own on the inbox, offset and dead-letter tables of schema `tenancy`. The handler runs in the consumer's
 * transaction on the tenant-bound [DSLContext], so the replica row, the inbox row and the offset commit together; an
 * envelope that breaks the contract goes to the dead letter. The default [RebuildRequiredSink] comes from
 * [ShortCodeUsedConsumerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class SubmissionDespatchedConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun submissionDespatchedConsumer(
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
            SubmissionDespatchedHandler(JooqDespatchFactStore(dsl)),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = TenancyEventCatalogue.SERVICE
        const val TOPIC = TenancyEventCatalogue.SUBMISSION_DESPATCHED_TOPIC
        const val SCHEMA = "tenancy"
    }
}
