package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.reconciliation.adapters.events.PeriodReassignedHandler
import com.dynokore.gst.reconciliation.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReadModel
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The three subscriptions of reconciliation-svc that fill its run read model (INV-REQ-1206): one inbox consumer per
 * topic, each on the inbox, offset and dead-letter tables of schema `reconciliation` (V1__reconciliation_schema.sql).
 * The handler runs in the consumer's transaction on the tenant-bound [DSLContext], so the read model write, the inbox
 * row and the offset commit together (INV-REQ-1222). The consumed topics are catalogued in [ReconciliationEvents].
 *
 * The beans are typed [InboxConsumer]: service-runtime's `replayDelivery` takes one `JdbcInboxConsumer` and is
 * ambiguous with three, so it backs off (the replay of a topic is not part of this slice).
 */
@Configuration(proxyBeanMethods = false)
class RunReadModelConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun canonicalVersionCreatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
    ): InboxConsumer =
        consumer(
            CANONICAL_VERSION_CREATED,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            CanonicalVersionCreatedHandler(JooqRunReadModel(dsl)),
        )

    @Bean
    @Suppress("LongParameterList")
    fun periodReassignedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
    ): InboxConsumer =
        consumer(
            PERIOD_REASSIGNED,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            PeriodReassignedHandler(JooqRunReadModel(dsl)),
        )

    @Bean
    @Suppress("LongParameterList")
    fun submissionAcknowledgedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
    ): InboxConsumer =
        consumer(
            SUBMISSION_ACKNOWLEDGED,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            SubmissionAcknowledgedHandler(JooqRunReadModel(dsl)),
        )

    @Suppress("LongParameterList")
    private fun consumer(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        handler: EventHandler,
    ): InboxConsumer {
        checkNotNull(
            catalogue.topic(subscription.topic),
        ) { "the event catalogue has no entry on ${subscription.topic}" }
        return JdbcInboxConsumer(subscription, catalogue, reader, scope, dsl, handler, rebuildRequired, clock)
    }

    companion object {
        const val CONSUMER = "reconciliation-svc"
        const val SCHEMA = "reconciliation"
        const val CANONICAL_VERSION_CREATED_TOPIC = "gst.canonical.canonical-version-created.v1"
        const val PERIOD_REASSIGNED_TOPIC = "gst.canonical.period-reassigned.v1"
        const val SUBMISSION_ACKNOWLEDGED_TOPIC = "gst.submission.submission-acknowledged.v1"

        val CANONICAL_VERSION_CREATED = ConsumerSubscription(CONSUMER, CANONICAL_VERSION_CREATED_TOPIC, SCHEMA)
        val PERIOD_REASSIGNED = ConsumerSubscription(CONSUMER, PERIOD_REASSIGNED_TOPIC, SCHEMA)
        val SUBMISSION_ACKNOWLEDGED = ConsumerSubscription(CONSUMER, SUBMISSION_ACKNOWLEDGED_TOPIC, SCHEMA)

        /** The subscriptions in registration order: one per topic. */
        val SUBSCRIPTIONS = listOf(CANONICAL_VERSION_CREATED, PERIOD_REASSIGNED, SUBMISSION_ACKNOWLEDGED)
    }
}
