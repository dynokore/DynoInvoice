package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * [EventLogReader] over the PostgreSQL event log: a consumer's SELECT on its subscribed topics (INV-REQ-1205), through
 * the claim path of ARC 4.3 step 1a (Keel INV-Q-3251 option D, INV-REQ-854; see [EventLogSchema]).
 *
 * - [claimSource] is the consumer's claim role: it finds the records after an offset across tenants from
 *   `log_offset` and `tenant_id` only, and measures the [backlog] from `log_offset` and `inserted_at`. It cannot
 *   read `envelope`.
 * - [envelopeSource] is the consumer's reader role, which sees rows only under the tenant policy. The envelopes of
 *   one claimed batch are read once per tenant, each in a transaction that first binds that tenant
 *   (`set_config('app.tenant_id', …, true)`, transaction-local as ARC-21 requires; `TenantId.PLATFORM` for platform
 *   events, INV-Q-2927), so no read can return another tenant's envelope.
 *
 * Records come back in `log_offset` order. If a claimed record is no longer readable when its envelope is read (a
 * retention purge removed it in between), the batch ends just before it, so no record after it is returned ahead
 * of it; the next read starts there again.
 *
 * Each read first reads the topic's purge watermark (INV-REQ-1205 b6, INV-Q-3249) in the same statement, so the same
 * snapshot, as the claim: when the caller's next position (`afterOffset + 1`) is at or below it, records the caller
 * has not consumed were purged, and [read] throws [RebuildRequired] instead of returning the next surviving record.
 * A topic never purged has no watermark row and reads as watermark 0.
 */
class PostgresEventLogReader(
    private val claimSource: DataSource,
    private val envelopeSource: DataSource,
) : EventLogReader,
    EventLogLag,
    EventLogHead {
    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> {
        val table = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        try {
            val (watermark, claimed) = claimSource.connection.use { claim(it, topic, table, afterOffset, limit) }
            if (afterOffset + 1 <= watermark) throw RebuildRequired(topic, afterOffset + 1, watermark)
            val envelopes = HashMap<Long, String>()
            claimed.groupBy({ it.second }, { it.first }).forEach { (tenant, offsets) ->
                envelopeSource.connection.use { envelopes.putAll(readBound(it, table, tenant, offsets)) }
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
        val table = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        try {
            return claimSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "select count(log_offset), min(inserted_at) from $table where log_offset > ?",
                    ).use { statement ->
                        statement.setLong(1, afterOffset)
                        statement.executeQuery().use {
                            it.next()
                            LogBacklog(it.getLong(1), it.getTimestamp(2)?.toInstant())
                        }
                    }
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log unreadable for $topic: ${failure.sqlState}", failure)
        }
    }

    override fun head(topic: String): TopicHead {
        val table = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        try {
            return claimSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "select (select coalesce(max(log_offset), 0) from $table), " +
                            "(select coalesce(max(watermark), 0) from $WATERMARK where topic = ?)",
                    ).use { statement ->
                        statement.setString(1, topic)
                        statement.executeQuery().use {
                            it.next()
                            TopicHead(it.getLong(1), it.getLong(2))
                        }
                    }
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log unreadable for $topic: ${failure.sqlState}", failure)
        }
    }

    /** The topic's watermark and the claimed (offset, tenant) pairs after [afterOffset], read in one snapshot. */
    private fun claim(
        connection: Connection,
        topic: String,
        table: String,
        afterOffset: Long,
        limit: Int,
    ): Pair<Long, List<Pair<Long, TenantId>>> = connection
        .prepareStatement(
            """
            select w.watermark, t.log_offset, t.tenant_id
            from (select coalesce(max(watermark), 0) as watermark from $WATERMARK where topic = ?) w
            left join lateral (
                select log_offset, tenant_id from $table where log_offset > ? order by log_offset limit ?
            ) t on true
            order by t.log_offset
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, topic)
            statement.setLong(2, afterOffset)
            statement.setInt(3, limit)
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

    private fun readBound(
        connection: Connection,
        table: String,
        tenant: TenantId,
        offsets: List<Long>,
    ): Map<Long, String> {
        connection.autoCommit = false
        try {
            connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.toString())
                it.execute()
            }
            val read =
                connection
                    .prepareStatement("select log_offset, envelope::text from $table where log_offset = any(?)")
                    .use { statement ->
                        statement.setArray(1, connection.createArrayOf("bigint", offsets.toTypedArray()))
                        statement.executeQuery().use { rows ->
                            buildMap { while (rows.next()) put(rows.getLong(1), rows.getString(2)) }
                        }
                    }
            connection.commit()
            return read
        } catch (failure: SQLException) {
            connection.rollback()
            throw failure
        }
    }

    private companion object {
        const val WATERMARK = "${EventLogSchema.SCHEMA}.${EventLogSchema.PURGE_WATERMARK}"
    }
}
