package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.events.ConsumedSubmissionEvents
import com.dynokore.gst.aggregation.adapters.events.SubmissionDespatchedHandler
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqHandOffStore
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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18: the consumer of `SubmissionDespatched` on the topic `gst.submission.submission-despatched.v1` (INV-REQ-439).
 * It marks the aggregate named by the optional `aggregateId` of the event as transmitted, once.
 */
@Configuration(proxyBeanMethods = false)
class SubmissionDespatchedConfiguration {
    @Bean
    fun submissionDespatchedHandler(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        store: JooqAggregateStore,
        mapper: ObjectMapper,
    ): SubmissionDespatchedHandler = SubmissionDespatchedHandler(JooqHandOffStore(dsl, runner, tenants, store), mapper)

    @Bean
    fun submissionDespatchedSubscription(): ConsumerSubscription =
        ConsumerSubscription(CONSUMER, ConsumedSubmissionEvents.SUBMISSION_DESPATCHED_TOPIC, SCHEMA)

    @Bean
    @Suppress("LongParameterList")
    fun submissionDespatchedConsumer(
        submissionDespatchedSubscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        dsl: DSLContext,
        submissionDespatchedHandler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            submissionDespatchedSubscription,
            catalogue,
            reader,
            scope,
            dsl,
            submissionDespatchedHandler,
            rebuildRequired,
            clock,
        )

    private companion object {
        const val CONSUMER = "aggregation-svc"
        const val SCHEMA = "aggregation"
    }
}
