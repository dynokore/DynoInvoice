package com.dynokore.platform.outboxandqueue

import java.sql.SQLException
import javax.sql.DataSource

/**
 * [EventLogPositions] on the keeper's runtime login ([eventLogSource], the event-log DataSource): one call of
 * `eventlog.record_position(topic, offset)` ([EventLogSchema.RECORD_POSITION_FUNCTION], owned by `eventlog_sys`), which
 * checks that `session_user` is the topic's keeper (coordinator ruling INV-Q-4266).
 */
class JdbcEventLogPositions(
    private val eventLogSource: DataSource,
) : EventLogPositions {
    override fun record(
        topic: String,
        offset: Long,
    ): Long {
        EventLogSchema.tableName(topic)
        require(offset >= 0) { "a record position is an offset of at least 0, was $offset" }
        try {
            return eventLogSource.connection.use { connection ->
                // its own transaction whatever the pool defaults to
                connection.autoCommit = true
                connection
                    .prepareStatement(
                        "select ${EventLogSchema.SCHEMA}.${EventLogSchema.RECORD_POSITION_FUNCTION}(?, ?)",
                    ).use { statement ->
                        statement.setString(1, topic)
                        statement.setLong(2, offset)
                        statement.executeQuery().use {
                            check(it.next()) { "record_position returned no row" }
                            it.getLong(1)
                        }
                    }
            }
        } catch (failure: SQLException) {
            throw EventBindingUnavailable("event log refused the position on $topic: ${failure.sqlState}", failure)
        }
    }
}
