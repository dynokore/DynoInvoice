package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
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
    ): InboxConsumer {
        val entry = entry(DECISION_TRACE_BEAN)
        return recording(
            entry.topic,
            scope,
            dsl,
            positions,
            meters,
            JdbcInboxConsumer(
                ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, entry.topic, ChainWiring.SCHEMA),
                catalogue,
                reader,
                scope,
                dsl,
                entry.handler(dsl, clock),
                rebuildRequired,
                clock,
            ),
        )
    }

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
    ): InboxConsumer {
        val entry = entry(AUDIT_ENTRY_BEAN)
        return recording(
            entry.topic,
            scope,
            dsl,
            positions,
            meters,
            JdbcInboxConsumer(
                ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, entry.topic, ChainWiring.SCHEMA),
                catalogue,
                reader,
                scope,
                dsl,
                entry.handler(dsl, clock),
                rebuildRequired,
                clock,
            ),
        )
    }

    /**
     * The subscription to `gst.platform.evidence-artefact-recorded.v1` (INV-REQ-1036 behavior 2): an ordinary inbox
     * consumer, not a record topic, so it keeps the `docsequence` check and takes no position writer.
     */
    @Bean
    @Suppress("LongParameterList")
    fun evidenceArtefactConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
    ): InboxConsumer {
        val entry = entry(EVIDENCE_ARTEFACT_BEAN)
        return JdbcInboxConsumer(
            ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, entry.topic, ChainWiring.SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            entry.handler(dsl, clock),
            rebuildRequired,
            clock,
        )
    }

    @Suppress("LongParameterList")
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
        const val EVIDENCE_ARTEFACT_TOPIC = "gst.platform.evidence-artefact-recorded.v1"

        const val DECISION_TRACE_BEAN = "decisionTraceConsumer"
        const val AUDIT_ENTRY_BEAN = "auditEntryConsumer"
        const val EVIDENCE_ARTEFACT_BEAN = "evidenceArtefactConsumer"

        /** One subscription of the registry: the consumer bean, its topic and the handler made for it. */
        internal class Entry(
            val bean: String,
            val topic: String,
            val handler: (DSLContext, Clock) -> EventHandler,
        )

        /** The one registry the three consumer beans are built from: each bean takes its topic and handler here. */
        internal val REGISTRY: List<Entry> =
            listOf(
                Entry(
                    DECISION_TRACE_BEAN,
                    DECISION_TRACE_TOPIC,
                ) { dsl, clock -> ChainWiring.decisionTraceHandler(dsl, clock) },
                Entry(AUDIT_ENTRY_BEAN, AUDIT_ENTRY_TOPIC) { dsl, clock -> ChainWiring.auditEntryHandler(dsl, clock) },
                Entry(EVIDENCE_ARTEFACT_BEAN, EVIDENCE_ARTEFACT_TOPIC) { dsl, clock ->
                    ChainWiring.evidenceArtefactHandler(dsl, clock)
                },
            )

        private fun entry(bean: String): Entry = REGISTRY.single { it.bean == bean }
    }
}
