package com.dynokore.platform.outboxandqueue

import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

/**
 * [RetentionPurge] over one service schema. It runs on [workerSource], the cross-tenant worker-role connection
 * (ARC 4.3 step 1a, never BYPASSRLS): [OutboxSchema.ddl] gives that role `DELETE` on exactly the expired rows,
 * published outbox rows, inbox rows and triaged dead letters, through its own policies. The cut-offs come from the injected [Clock].
 */
class JdbcRetentionPurge(
    private val workerSource: DataSource,
    schema: String,
    private val retention: Retention,
    private val clock: Clock,
    private val batchSize: Int = BATCH,
) : RetentionPurge {
    private val s = SqlIdentifier.require(schema, "schema")

    init {
        require(batchSize > 0) { "batch size must be positive" }
    }

    override fun purge(): PurgeResult {
        val now = clock.instant()
        val outbox = deleteBatches(
            "delete from $s.${OutboxSchema.OUTBOX} where seq in " +
                "(select seq from $s.${OutboxSchema.OUTBOX} where published_at is not null and published_at < ? limit ?)",
            Timestamp.from(now - retention.contentWindow),
        )
        val inbox = deleteBatches(
            "delete from $s.${OutboxSchema.INBOX} where (consumer, event_id) in " +
                "(select consumer, event_id from $s.${OutboxSchema.INBOX} where received_at < ? limit ?)",
            Timestamp.from(now - retention.inboxRetention),
        )
        val deadLetters = deleteBatches(
            "delete from $s.${OutboxSchema.DEAD_LETTER} where id in " +
                "(select id from $s.${OutboxSchema.DEAD_LETTER} " +
                "where status <> 'OPEN' and resolved_at is not null and resolved_at < ? limit ?)",
            Timestamp.from(now - retention.contentWindow),
        )
        return PurgeResult(outbox, inbox, deadLetters)
    }

    private fun deleteBatches(
        sql: String,
        cutoff: Timestamp,
    ): Long {
        var total = 0L
        workerSource.connection.use { connection ->
            // Each batch is its own transaction whatever the pool defaults to (an autoCommit=false pool would run the
            // whole loop as one transaction and roll it back on close).
            val wasAutoCommit = connection.autoCommit
            connection.autoCommit = true
            try {
                connection.prepareStatement(sql).use { statement ->
                    do {
                        statement.setTimestamp(1, cutoff)
                        statement.setInt(2, batchSize)
                        val deleted = statement.executeUpdate()
                        total += deleted
                    } while (deleted == batchSize)
                }
            } finally {
                connection.autoCommit = wasAutoCommit
            }
        }
        return total
    }

    private companion object {
        const val BATCH = 1000
    }
}
