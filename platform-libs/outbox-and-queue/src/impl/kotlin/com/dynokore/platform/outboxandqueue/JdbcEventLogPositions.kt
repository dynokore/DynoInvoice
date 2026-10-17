package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * [EventLogPositions] on the keeper's runtime login (the event-log [eventLog] scope): one call of
 * `eventlog.record_position(topic, offset)` ([EventLogSchema.RECORD_POSITION_FUNCTION], owned by `eventlog_sys`), which
 * checks that `session_user` is the topic's keeper and no tenant (coordinator rulings INV-Q-4266, INV-Q-5614). The call
 * is a jOOQ function call in its own transaction under the platform binding, on the event-log scope's own runner.
 */
class JdbcEventLogPositions(
    private val eventLog: TenantScope,
    private val dsl: DSLContext,
) : EventLogPositions {
    override fun record(
        topic: String,
        offset: Long,
    ): Long {
        EventLogSchema.tableName(topic)
        require(offset >= 0) { "a record position is an offset of at least 0, was $offset" }
        try {
            return eventLog.bindWorkload(TenantId.PLATFORM) {
                eventLog.transactions.inTransaction {
                    val position =
                        DSL.function(
                            DSL.name(EventLogSchema.SCHEMA, EventLogSchema.RECORD_POSITION_FUNCTION),
                            SQLDataType.BIGINT,
                            DSL.`val`(topic, SQLDataType.CLOB),
                            DSL.`val`(offset, SQLDataType.BIGINT),
                        )
                    val row = dsl.select(position).fetchOne()
                    check(row != null) { "record_position returned no row" }
                    checkNotNull(row.get(position)) { "record_position returned no position" }
                }
            }
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            // jOOQ wraps the SQLException; the SQLSTATE is read through the runner (INV-Q-5121)
            val state = eventLog.sqlErrors.sqlStateOf(failure) ?: throw failure
            throw EventBindingUnavailable("event log refused the position on $topic: $state", failure)
        }
    }
}
