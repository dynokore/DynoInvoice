package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.ConsumerOffset.Companion.CONSUMER_OFFSET
import com.dynokore.platform.outboxandqueue.EventLogPositions
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes evidence-svc's committed position on one record topic to the topic's `eventlog` record-position row, through
 * outbox-and-queue's [EventLogPositions] (`eventlog.record_position(topic, offset)`; INV-REQ-3650 b2, INV-REQ-1205 b3,
 * coordinator ruling INV-Q-4266). [recordAfterCommit] runs after the consumer's transaction has committed and reads
 * the committed offset from the consumer's own offset row, so the position written is never ahead of what is stored;
 * the function moves it forward only. A failed write is counted and logged, never thrown: the next call writes the
 * then-current committed offset, so a failed or lagging write is retried after the next commit and only holds the
 * purge back longer (INV-REQ-3650 b3, INV-REQ-1205 b4).
 *
 * Meters per record topic (CS-36, label `topic` with the two record topics): `gst.evidence.record.committed.offset`,
 * `gst.evidence.record.position`, `gst.evidence.record.position.gap` and `gst.evidence.record.position.write.failures`
 * (a counter, rendered with `_total`).
 */
class RecordPositionWriter(
    private val consumer: String,
    private val topic: String,
    private val scope: TenantScope,
    private val dsl: DSLContext,
    private val positions: EventLogPositions,
    registry: MeterRegistry,
) {
    private val committed = AtomicLong(0)
    private val recorded = AtomicLong(0)
    private val failures: Counter =
        Counter
            .builder(FAILURES)
            .description("Failed writes of the committed position to the event-log record-position row")
            .tag(TOPIC_TAG, topic)
            .register(registry)

    init {
        Gauge
            .builder(COMMITTED, committed) { it.get().toDouble() }
            .description("The committed log offset of evidence-svc on a record topic")
            .tag(TOPIC_TAG, topic)
            .register(registry)
        Gauge
            .builder(POSITION, recorded) { it.get().toDouble() }
            .description("The position evidence-svc last recorded on a record topic")
            .tag(TOPIC_TAG, topic)
            .register(registry)
        Gauge
            .builder(GAP, this) { (it.committed.get() - it.recorded.get()).toDouble() }
            .description("Committed offset less recorded position of a record topic")
            .tag(TOPIC_TAG, topic)
            .register(registry)
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // a failed position write must never reach, let alone stop, consumption
    fun recordAfterCommit() {
        try {
            val offset = committedOffset()
            committed.set(offset)
            if (offset > recorded.get()) {
                recorded.set(maxOf(recorded.get(), positions.record(topic, offset)))
            }
        } catch (failure: Exception) {
            failures.increment()
            log.warn("the position write on {} failed, to be retried after the next commit", topic, failure)
        }
    }

    /** The consumer's `next_offset` less one (1 before any record, so 0 means nothing is committed yet). */
    private fun committedOffset(): Long =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                val next =
                    dsl
                        .select(CONSUMER_OFFSET.NEXT_OFFSET)
                        .from(CONSUMER_OFFSET)
                        .where(CONSUMER_OFFSET.CONSUMER.eq(consumer))
                        .and(CONSUMER_OFFSET.TOPIC.eq(topic))
                        .fetchOne(0, Long::class.java)
                (next ?: 1L) - 1L
            }
        }

    companion object {
        const val COMMITTED = "gst.evidence.record.committed.offset"
        const val POSITION = "gst.evidence.record.position"
        const val GAP = "gst.evidence.record.position.gap"
        const val FAILURES = "gst.evidence.record.position.write.failures"
        const val TOPIC_TAG = "topic"
        private val log = LoggerFactory.getLogger(RecordPositionWriter::class.java)
    }
}
