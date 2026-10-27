package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogPositions
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscriptions of evidence-svc to the two record topics (INV-REQ-3650 b1, INV-REQ-1226 b4), one inbox consumer
 * each on the inbox, offset and dead-letter tables of schema `evidence`. Both types are catalogued as record types
 * ([EvidenceEvents.recordEntries]), so the consumer takes no `docsequence` check for them (INV-Q-3809). The handler
 * runs in the consumer's transaction, so the record, its `applied_record` row, the inbox row and the offset commit
 * together (INV-REQ-1222). After each poll the position of the topic is written
 * through [PositionRecordingConsumer] (INV-T-4271).
 */
@Configuration(proxyBeanMethods = false)
class RecordConsumerConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun decisionTraceConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        positions: EventLogPositions,
        meters: MeterRegistry,
    ): InboxConsumer =
        recording(
            DECISION_TRACE_TOPIC,
            scope,
            dsl,
            positions,
            meters,
            JdbcInboxConsumer(
                ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, DECISION_TRACE_TOPIC, ChainWiring.SCHEMA),
                catalogue,
                reader,
                scope,
                dsl,
                ChainWiring.decisionTraceHandler(dsl, clock),
                rebuildRequired,
                clock,
            ),
        )

    @Bean
    @Suppress("LongParameterList")
    fun auditEntryConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        positions: EventLogPositions,
        meters: MeterRegistry,
    ): InboxConsumer =
        recording(
            AUDIT_ENTRY_TOPIC,
            scope,
            dsl,
            positions,
            meters,
            JdbcInboxConsumer(
                ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, AUDIT_ENTRY_TOPIC, ChainWiring.SCHEMA),
                catalogue,
                reader,
                scope,
                dsl,
                ChainWiring.auditEntryHandler(dsl, clock),
                rebuildRequired,
                clock,
            ),
        )

    private fun recording(
        topic: String,
        scope: TenantScope,
        dsl: DSLContext,
        positions: EventLogPositions,
        meters: MeterRegistry,
        consumer: InboxConsumer,
    ): InboxConsumer =
        PositionRecordingConsumer(
            consumer,
            RecordPositionWriter(LocatorConsumerConfiguration.CONSUMER, topic, scope, dsl, positions, meters),
        )

    companion object {
        const val DECISION_TRACE_TOPIC = "gst.canonical.decision-trace-recorded.v1"
        const val AUDIT_ENTRY_TOPIC = "gst.platform.audit-entry-recorded.v1"
    }
}
