package com.dynokore.platform.outboxandqueue

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

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
    private val purgeSource: DataSource,
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
        // The migration that generated purge_batch() needed one marker per topic; a catalogue without it is refused here.
        catalogue.entries().groupBy { it.topic }.forEach { (topic, entries) ->
            val markers = entries.map { it.contentTopic }.toSet()
            requireNotNull(markers.singleOrNull()) {
                "topic $topic carries no single content_topic marker in topics.yaml, was $markers (INV-REQ-1205 b4)"
            }
        }
    }

    override fun purge(): EventLogPurgeRun {
        val totals = LinkedHashMap<String, TopicPurge>()
        try {
            purgeSource.connection.use { connection ->
                // Each call is its own transaction whatever the pool defaults to; the deletes and the watermarks of one
                // call commit together.
                val wasAutoCommit = connection.autoCommit
                connection.autoCommit = true
                try {
                    do {
                        val batch = batch(connection)
                        batch.forEach { purged ->
                            totals.merge(purged.topic, purged) { first, later ->
                                later.copy(rows = first.rows + later.rows, period = first.period, cutoff = first.cutoff)
                            }
                        }
                    } while (batch.any { it.rows >= EventLogSchema.PURGE_BATCH_SIZE })
                } finally {
                    connection.autoCommit = wasAutoCommit
                }
            }
        } catch (failure: SQLException) {
            LOG.atError().setMessage("event-log purge failed").addKeyValue("sql_state", failure.sqlState).log()
            throw EventBindingUnavailable("event log not purgeable: ${failure.sqlState}", failure)
        }
        if (totals.isEmpty()) {
            LOG.atError().setMessage("event-log retention is missing or below 7 days: nothing purged (INV-Q-4262)").log()
        }
        val run = EventLogPurgeRun(totals.values.toList())
        run.topics.forEach {
            LOG
                .atInfo()
                .setMessage("event-log purge run")
                .addKeyValue("topic", it.topic)
                .addKeyValue("content_topic", it.contentTopic)
                .addKeyValue("period", it.period)
                .addKeyValue("cutoff", it.cutoff)
                .addKeyValue("rows", it.rows)
                .addKeyValue("watermark", it.watermark)
                .log()
        }
        return run
    }

    /** One `purge_batch()` call: one row per topic. */
    private fun batch(connection: Connection): List<TopicPurge> = connection
        .prepareStatement(
            """
            select purged_topic, purged_content, extract(epoch from purged_period)::bigint, purged_cutoff, purged_rows,
                purged_watermark
            from ${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_BATCH}()
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val key = rows.getString(1)
                        add(
                            TopicPurge(
                                topic = names[key] ?: key,
                                contentTopic = rows.getBoolean(2),
                                period = Duration.ofSeconds(rows.getLong(3)),
                                cutoff = rows.getTimestamp(4).toInstant(),
                                rows = rows.getLong(5),
                                watermark = rows.getLong(6),
                            ),
                        )
                    }
                }
            }
        }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(JdbcEventLogPurge::class.java)
    }
}
