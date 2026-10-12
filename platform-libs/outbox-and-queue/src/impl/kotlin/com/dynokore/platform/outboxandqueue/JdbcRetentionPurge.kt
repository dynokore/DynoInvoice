package com.dynokore.platform.outboxandqueue

import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

/**
 * [RetentionPurge] over one service schema. It runs on [runtimeSource], a connection of the service's runtime login,
 * and deletes only through the `<svc>_sys`-owned definer functions of [OutboxSchema.ddl]
 * ([OutboxSchema.OUTBOX_PURGE_EXPIRED], [OutboxSchema.INBOX_PURGE_EXPIRED], [OutboxSchema.DEAD_LETTER_PURGE_EXPIRED];
 * INV-Q-3953, INV-ADR-3968): each holds its own age predicate and refuses a cut-off later than the retention floor,
 * so the runtime login can delete only expired rows, and none directly. The cut-offs come from the injected [Clock].
 */
class JdbcRetentionPurge(
    private val runtimeSource: DataSource,
    schema: String,
    private val retention: Retention,
    private val clock: Clock,
    private val batchSize: Int = BATCH,
) : RetentionPurge {
    private val s = SqlIdentifier.require(schema, "schema")

    init {
        require(batchSize in 1..OutboxSchema.MAX_PURGE_BATCH) { "batch size must be 1..${OutboxSchema.MAX_PURGE_BATCH}" }
    }

    override fun purge(): PurgeResult {
        val now = clock.instant()
        val outbox = deleteBatches(OutboxSchema.OUTBOX_PURGE_EXPIRED, Timestamp.from(now - retention.contentWindow))
        val inbox = deleteBatches(OutboxSchema.INBOX_PURGE_EXPIRED, Timestamp.from(now - retention.inboxRetention))
        val deadLetters =
            deleteBatches(OutboxSchema.DEAD_LETTER_PURGE_EXPIRED, Timestamp.from(now - retention.contentWindow))
        return PurgeResult(outbox, inbox, deadLetters)
    }

    private fun deleteBatches(
        function: String,
        cutoff: Timestamp,
    ): Long {
        var total = 0L
        runtimeSource.connection.use { connection ->
            // Each batch is its own transaction whatever the pool defaults to (an autoCommit=false pool would run the
            // whole loop as one transaction and roll it back on close).
            val wasAutoCommit = connection.autoCommit
            connection.autoCommit = true
            try {
                connection.prepareStatement("select $s.$function(?, ?)").use { statement ->
                    do {
                        statement.setTimestamp(1, cutoff)
                        statement.setInt(2, batchSize)
                        val deleted = statement.executeQuery().use { rows ->
                            check(rows.next()) { "$function returned no row" }
                            rows.getLong(1)
                        }
                        total += deleted
                    } while (deleted == batchSize.toLong())
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
