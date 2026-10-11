package com.dynokore.platform.outboxandqueue

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * [EventLogPurge] over the PostgreSQL event log, run by the event-log component (INV-MOD-2748) on [purgeSource], its
 * dedicated purge role ([EventLogSchema.topicDdl]: `DELETE`, `SELECT` on `log_offset` and `inserted_at`, write on the
 * purge-watermark table; never BYPASSRLS).
 *
 * The period of each topic of [catalogue] comes from its `content_topic` marker only (INV-REQ-1205 b4): the content
 * window for a content topic, the event-log retention for any other. [Retention] refuses either below 7 days. A topic
 * whose catalogue entry carries no marker is refused at construction rather than given a guessed period. The replay
 * topics (INV-T-3606) are purged by their own `content_topic` marker and the request topics as non-content topics, like
 * every topic (INV-REQ-1205 b4).
 *
 * Each batch is one statement, so one transaction: it deletes up to [batchSize] rows with `inserted_at` before the
 * cut-off, lowest offsets first, and raises the topic's watermark to the highest offset it deleted (never lowers it).
 * Consumer positions are never read. Each run logs, per topic, the rows deleted, the cut-off and the watermark.
 */
class JdbcEventLogPurge
@JvmOverloads
constructor(
    private val purgeSource: DataSource,
    catalogue: EventCatalogue,
    private val retention: Retention,
    private val clock: Clock,
    private val batchSize: Int = BATCH,
) : EventLogPurge {
    private class Topic(
        val topic: String,
        val table: String,
        val content: Boolean,
    )

    private val topics: List<Topic> =
        catalogue.entries().groupBy { it.topic }.toSortedMap().map { (topic, entries) ->
            val markers = entries.map { it.contentTopic }.toSet()
            val content =
                requireNotNull(markers.singleOrNull()) {
                    "topic $topic carries no single content_topic marker in topics.yaml, was $markers (INV-REQ-1205 b4)"
                }
            Topic(topic, "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}", content)
        } + catalogue.replayTopics().map { Topic(it.topic, "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(it.topic)}", it.contentTopic) } +
            catalogue.requestTopics().map { Topic(it.topic, "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(it.topic)}", false) }

    init {
        require(batchSize > 0) { "batch size must be positive" }
    }

    override fun purge(): EventLogPurgeRun {
        val now = clock.instant()
        val run =
            EventLogPurgeRun(
                topics.map { topic ->
                    val period = if (topic.content) retention.contentWindow else retention.eventLogRetention
                    purgeTopic(topic, period, now - period)
                },
            )
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

    private fun purgeTopic(
        topic: Topic,
        period: Duration,
        cutoff: Instant,
    ): TopicPurge {
        var rows = 0L
        var watermark = 0L
        try {
            purgeSource.connection.use { connection ->
                // Each batch is its own transaction whatever the pool defaults to; the delete and the watermark are
                // one statement, so they commit together.
                val wasAutoCommit = connection.autoCommit
                connection.autoCommit = true
                try {
                    connection.prepareStatement(batchSql(topic.table)).use { statement ->
                        do {
                            statement.setTimestamp(1, Timestamp.from(cutoff))
                            statement.setInt(2, batchSize)
                            statement.setString(3, topic.topic)
                            val deleted =
                                statement.executeQuery().use {
                                    it.next()
                                    it.getLong(1)
                                }
                            rows += deleted
                        } while (deleted == batchSize.toLong())
                    }
                    watermark = watermarkOf(connection, topic.topic)
                } finally {
                    connection.autoCommit = wasAutoCommit
                }
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log not purgeable for ${topic.topic}: ${failure.sqlState}", failure)
        }
        return TopicPurge(topic.topic, topic.content, period, cutoff, rows, watermark)
    }

    private fun watermarkOf(
        connection: Connection,
        topic: String,
    ): Long = connection.prepareStatement("select watermark from $WATERMARK where topic = ?").use {
        it.setString(1, topic)
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

    private fun batchSql(table: String): String = """
        with gone as (
            delete from $table where log_offset in (
                select log_offset from $table where inserted_at < ? order by log_offset limit ?
            ) returning log_offset
        ), marked as (
            insert into $WATERMARK as w (topic, watermark, updated_at)
            select ?, max(log_offset), now() from gone having count(*) > 0
            on conflict (topic) do update
                set watermark = greatest(w.watermark, excluded.watermark), updated_at = excluded.updated_at
            returning watermark
        )
        select (select count(*) from gone), (select count(*) from marked)
    """.trimIndent()

    private companion object {
        const val BATCH = 1000
        const val WATERMARK = "${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK}"
        val LOG: Logger = LoggerFactory.getLogger(JdbcEventLogPurge::class.java)
    }
}
