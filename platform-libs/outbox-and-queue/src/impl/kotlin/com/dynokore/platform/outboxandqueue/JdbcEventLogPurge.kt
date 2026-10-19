package com.dynokore.platform.outboxandqueue

import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * [EventLogPurge] over the PostgreSQL event log, run by the event-log component (INV-MOD-2748) on [purgeSource], its
 * runtime login, which holds EXECUTE on `eventlog.purge_batch()` and no right on the topic tables (owner ruling
 * INV-Q-3953, INV-REQ-1205 b3).
 *
 * `purge_batch()` ([EventLogSchema.PURGE_BATCH], owned by `eventlog_sys`) does the work: per topic, by age only, the
 * content window for a content topic and the event-log retention for any other, both read from the event-log retention
 * row (coordinator ruling INV-Q-4262), never above a record topic's recorded position, and the watermark raised in the
 * same transaction. Each call is its own transaction; this class calls it until no topic has a full batch left, sums
 * the rows per topic and logs, per topic, the rows deleted, the cut-off and the watermark. When the retention row is
 * missing or below 7 days the function deletes nothing and returns no row: the run logs an error and is empty.
 *
 * [catalogue] names the topics in the result: a topic's key ([EventLogSchema.tableName]) is mapped back to its name.
 */
class JdbcEventLogPurge(
    private val eventLog: TenantScope,
    private val dsl: DSLContext,
    catalogue: EventCatalogue,
) : EventLogPurge {
    private val names: Map<String, String> =
        (
            catalogue.entries().map { it.topic } +
                catalogue.replayTopics().map { it.topic } +
                catalogue.requestTopics().map { it.topic }
        ).distinct()
            .associateBy { EventLogSchema.tableName(it) }

    init {
        // The migration that generated purge_batch() needed one marker per topic; a catalogue without it is refused
        // here.
        catalogue.entries().groupBy { it.topic }.forEach { (topic, entries) ->
            val markers = entries.map { it.contentTopic }.toSet()
            requireNotNull(markers.singleOrNull()) {
                "topic $topic carries no single content_topic marker in topics.yaml, was $markers (INV-REQ-1205 b4)"
            }
            val retained = entries.map { it.retainedWhileResolvable }.toSet()
            val retainedMarker =
                requireNotNull(retained.singleOrNull()) {
                    "topic $topic carries no single retained_while_resolvable marker in topics.yaml, was $retained " +
                        "(INV-REQ-1210)"
                }
            require(!(retainedMarker && markers.single() == true)) {
                "topic $topic is marked both content_topic and retained_while_resolvable (INV-REQ-1210)"
            }
        }
    }

    override fun purge(): EventLogPurgeRun {
        val totals = LinkedHashMap<String, TopicPurge>()
        try {
            do {
                // Each call is its own transaction: the deletes and the watermarks of one call commit together.
                val batch = eventLog.platformTransaction { batch() }
                batch.forEach { purged ->
                    totals.merge(purged.topic, purged) { first, later ->
                        later.copy(rows = first.rows + later.rows, period = first.period, cutoff = first.cutoff)
                    }
                }
            } while (batch.any { it.rows >= EventLogSchema.PURGE_BATCH_SIZE })
        } catch (expected: Exception) {
            // jOOQ wraps the SQLException; the SQLSTATE is read through the runner (INV-Q-5121)
            val state = eventLog.sqlErrors.sqlStateOf(expected) ?: throw expected
            LOG
                .atError()
                .setMessage("event-log purge failed")
                .addKeyValue("sql_state", state)
                .log()
            throw EventBindingUnavailable("event log not purgeable: $state", expected)
        }
        if (totals.isEmpty()) {
            LOG
                .atError()
                .setMessage(
                    "event-log retention is missing or below 7 days: nothing purged (INV-Q-4262)",
                ).log()
        }
        val run = EventLogPurgeRun(totals.values.toList())
        run.topics.forEach { logRun(it) }
        return run
    }

    private fun logRun(purged: TopicPurge) {
        LOG
            .atInfo()
            .setMessage("event-log purge run")
            .addKeyValue("topic", purged.topic)
            .addKeyValue("content_topic", purged.contentTopic)
            .addKeyValue("retained_while_resolvable", purged.retainedWhileResolvable)
            .addKeyValue("period", purged.period)
            .addKeyValue("cutoff", purged.cutoff)
            .addKeyValue("rows", purged.rows)
            .addKeyValue("watermark", purged.watermark)
            .log()
    }

    /** One `purge_batch()` call: one row per topic. */
    private fun batch(): List<TopicPurge> {
        val purged = EventLogPurgeBatch()
        // the period in whole seconds, as the function's interval
        val seconds = DSL.extract(purged.period, DatePart.EPOCH).cast(SQLDataType.BIGINT)
        return dsl
            .select(
                purged.topic,
                purged.content,
                purged.retained,
                seconds,
                purged.cutoff,
                purged.rows,
                purged.watermark,
            ).from(purged)
            .fetch()
            .map { row ->
                val key = row.get(purged.topic)
                TopicPurge(
                    topic = names[key] ?: key,
                    contentTopic = row.get(purged.content),
                    period = Duration.ofSeconds(checkNotNull(row.get(seconds))),
                    cutoff = row.get(purged.cutoff).toInstant(),
                    rows = row.get(purged.rows),
                    watermark = row.get(purged.watermark),
                    retainedWhileResolvable = row.get(purged.retained),
                )
            }
    }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(JdbcEventLogPurge::class.java)
    }
}
