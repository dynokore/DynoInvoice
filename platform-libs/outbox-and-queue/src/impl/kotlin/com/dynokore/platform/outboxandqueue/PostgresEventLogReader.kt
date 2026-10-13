package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [EventLogReader] over the PostgreSQL event log: a consumer's read of its subscribed topics (INV-REQ-1205 b3), on the
 * service's runtime login ([eventLogSource], the event-log DataSource) and nothing else.
 *
 * - The records after an offset are found across tenants only through `eventlog.claim` (owned by `eventlog_sys`,
 *   [EventLogSchema.CLAIM]), which returns `log_offset`, `tenant_id` and `inserted_at` of the topics the login subscribes
 *   to, and nothing for any other topic.
 * - The envelopes of one claimed batch are read once per tenant through [eventLog], each in a transaction that binds that
 *   tenant and the service's workload principal first (tenancy-kernel, ARC-21; `TenantId.PLATFORM` for platform events,
 *   INV-Q-2927), under the tenant policy, so no read can return another tenant's envelope.
 *
 * Records come back in `log_offset` order. If a claimed record is no longer readable when its envelope is read (a
 * retention purge removed it in between), the batch ends just before it, so no record after it is returned ahead
 * of it; the next read starts there again.
 *
 * Each read first reads the topic's purge watermark (INV-REQ-1205 b6, INV-Q-3249) in the same statement, so the same
 * snapshot (`claim` is STABLE), as the claim: when the caller's next position (`afterOffset + 1`) is at or below it,
 * records the caller has not consumed were purged, and [read] throws [RebuildRequired] instead of returning the next
 * surviving record. A topic never purged has no watermark row and reads as watermark 0.
 *
 * The [backlog] and the [head] are measured through `claim` only (coordinator ruling INV-Q-4264): the oldest unconsumed
 * record is one single-row claim after the position, and the head is found by a galloping search of single-row claims
 * (exponential, then binary) from the given position. The backlog count is the offset lag (head minus position), an
 * upper bound of the records unconsumed, since offsets of rolled-back inserts and purged rows count.
 */
class PostgresEventLogReader(
    private val eventLogSource: DataSource,
    private val eventLog: TenantScope,
) : EventLogReader,
    EventLogLag,
    EventLogHead {
    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> {
        val topicTable = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        try {
            val (watermark, claimed) = eventLogSource.connection.use { claimBatch(it, topic, afterOffset, limit) }
            if (afterOffset + 1 <= watermark) throw RebuildRequired(topic, afterOffset + 1, watermark)
            val envelopes = HashMap<Long, String>()
            claimed.groupBy({ it.second }, { it.first }).forEach { (tenant, offsets) ->
                eventLog.bindWorkload(tenant) {
                    eventLog.transactions.inTransaction {
                        val connection = eventLog.connections.require()
                        connection
                            .prepareStatement(
                                "select log_offset, envelope::text from $topicTable where log_offset = any(?)",
                            ).use { statement ->
                                statement.setArray(1, connection.createArrayOf("bigint", offsets.toTypedArray()))
                                statement.executeQuery().use { rows ->
                                    while (rows.next()) envelopes[rows.getLong(1)] = rows.getString(2)
                                }
                            }
                    }
                }
            }
            return claimed
                .asSequence()
                .map { (offset, _) -> envelopes[offset]?.let { LogRecord(offset, it) } }
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log unreadable for $topic: ${failure.sqlState}", failure)
        }
    }

    override fun backlog(
        topic: String,
        afterOffset: Long,
    ): LogBacklog {
        try {
            return eventLogSource.connection.use { connection ->
                val oldest = next(connection, topic, afterOffset) ?: return@use LogBacklog(0, null)
                val head = gallop(connection, topic, afterOffset, oldest.first)
                LogBacklog(head - afterOffset, oldest.second)
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log unreadable for $topic: ${failure.sqlState}", failure)
        }
    }

    override fun head(
        topic: String,
        from: Long,
    ): TopicHead {
        require(from >= 0) { "a head search starts at an offset of at least 0, was $from" }
        try {
            return eventLogSource.connection.use { connection ->
                val watermark = watermark(connection, topic)
                val start = maxOf(from, watermark)
                val first = next(connection, topic, start)
                val head = if (first == null) start else gallop(connection, topic, start, first.first)
                TopicHead(head, watermark)
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log unreadable for $topic: ${failure.sqlState}", failure)
        }
    }

    /**
     * The highest offset present, given [present], an offset present after [from]: doubles a step from [present] until a
     * single-row claim after `present + step` finds nothing, then halves the interval between the highest offset found
     * and that bound. About two claims per doubling of the distance from [from] to the head.
     */
    private fun gallop(
        connection: Connection,
        topic: String,
        from: Long,
        present: Long,
    ): Long {
        require(present > from) { "the offset found lies after the start" }
        var low = present // present on the topic
        var step = 1L
        var high: Long // nothing present after it, as far as the search has seen
        while (true) {
            val probe = if (low > Long.MAX_VALUE - step) Long.MAX_VALUE else low + step
            val found = next(connection, topic, probe)?.first
            if (found == null) {
                high = probe
                break
            }
            low = found
            step = if (step > Long.MAX_VALUE / 2) Long.MAX_VALUE else step * 2
        }
        while (low < high) {
            val middle = low + (high - low) / 2
            val found = next(connection, topic, middle)?.first
            if (found == null) {
                high = middle
            } else {
                low = found
                if (found > high) high = found // inserted while searching: the head is at least that
            }
        }
        return low
    }

    /** The first record after [afterOffset] as `claim` returns it: (log_offset, inserted_at), or null when none. */
    private fun next(
        connection: Connection,
        topic: String,
        afterOffset: Long,
    ): Pair<Long, Instant>? =
        connection
            .prepareStatement(
                "select log_offset, inserted_at from ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(?, ?, 1)",
            ).use { statement ->
                statement.setString(1, topic)
                statement.setLong(2, afterOffset)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) to rows.getTimestamp(2).toInstant() else null
                }
            }

    private fun watermark(
        connection: Connection,
        topic: String,
    ): Long =
        connection
            .prepareStatement("select coalesce(max(watermark), 0) from $WATERMARK where topic_key = ?")
            .use { statement ->
                statement.setString(1, EventLogSchema.tableName(topic))
                statement.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }

    /** The topic's watermark and the claimed (offset, tenant) pairs after [afterOffset], read in one snapshot. */
    private fun claimBatch(
        connection: Connection,
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): Pair<Long, List<Pair<Long, TenantId>>> =
        connection
            .prepareStatement(
                """
                select w.watermark, c.log_offset, c.tenant_id
                from (select coalesce(max(watermark), 0) as watermark from $WATERMARK where topic_key = ?) w
                left join lateral ${EventLogSchema.SCHEMA}.${EventLogSchema.CLAIM}(?, ?, ?) c on true
                order by c.log_offset
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, EventLogSchema.tableName(topic))
                statement.setString(2, topic)
                statement.setLong(3, afterOffset)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows ->
                    var watermark = 0L
                    val claimed =
                        buildList {
                            while (rows.next()) {
                                watermark = rows.getLong(1)
                                val offset = rows.getLong(2)
                                if (!rows.wasNull()) add(offset to TenantId(rows.getObject(3, UUID::class.java)))
                            }
                        }
                    watermark to claimed
                }
            }

    private companion object {
        const val WATERMARK = "${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK}"
    }
}
