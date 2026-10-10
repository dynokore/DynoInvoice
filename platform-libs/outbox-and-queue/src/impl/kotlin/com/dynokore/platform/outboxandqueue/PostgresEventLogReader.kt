package com.dynokore.platform.outboxandqueue

import java.sql.SQLException
import javax.sql.DataSource

/** [EventLogReader] over the PostgreSQL event log: a consumer's SELECT on its subscribed topics (INV-REQ-1205). */
class PostgresEventLogReader(
    private val dataSource: DataSource,
) : EventLogReader,
    EventLogLag {
    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> {
        val table = "${EventLogSchema.SCHEMA}.${EventLogSchema.tableName(topic)}"
        try {
            return dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "select log_offset, envelope::text from $table " +
                            "where log_offset > ? order by log_offset limit ?",
                    ).use { statement ->
                        statement.setLong(1, afterOffset)
                        statement.setInt(2, limit)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(LogRecord(rows.getLong(1), rows.getString(2)))
                            }
                        }
                    }
            }
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
            return dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "select count(*), min(inserted_at) from $table where log_offset > ?",
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
}
