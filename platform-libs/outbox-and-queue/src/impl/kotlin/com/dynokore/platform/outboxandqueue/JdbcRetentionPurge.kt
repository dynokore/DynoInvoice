package com.dynokore.platform.outboxandqueue

import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

/**
 * [RetentionPurge] over one service schema. It runs on [workerSource], the cross-tenant worker-role connection
 * (ARC 4.3 step 1a, never BYPASSRLS): [OutboxSchema.ddl] gives that role `DELETE` on exactly the expired rows,
 * published outbox rows and inbox rows, through its own policies. The cut-offs come from the injected [Clock].
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
        return PurgeResult(outbox, inbox)
    }

    private fun deleteBatches(
        sql: String,
        cutoff: Timestamp,
    ): Long {
        var total = 0L
        workerSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                do {
                    statement.setTimestamp(1, cutoff)
                    statement.setInt(2, batchSize)
                    val deleted = statement.executeUpdate()
                    total += deleted
                } while (deleted == batchSize)
            }
        }
        return total
    }

    private companion object {
        const val BATCH = 1000
    }
}
