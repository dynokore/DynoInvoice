package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [RetentionPurge] over one service schema. It runs on [scope], the service's runtime login, and deletes only through
 * the `<svc>_sys`-owned definer functions of [OutboxSchema.ddl]
 * ([OutboxSchema.OUTBOX_PURGE_EXPIRED], [OutboxSchema.INBOX_PURGE_EXPIRED], [OutboxSchema.DEAD_LETTER_PURGE_EXPIRED];
 * INV-Q-3953, INV-ADR-3968), called as jOOQ routine calls on [dsl]: each holds its own age predicate and refuses a
 * cut-off later than the retention floor, so the runtime login can delete only expired rows, and none directly. The
 * cut-offs come from the injected [Clock]. Each batch is a transaction of its own under the platform binding.
 */
class JdbcRetentionPurge(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val retention: Retention,
    private val clock: Clock,
    private val batchSize: Int = BATCH,
) : RetentionPurge {
    private val s = SqlIdentifier.require(schema, "schema")

    init {
        require(
            batchSize in 1..OutboxSchema.MAX_PURGE_BATCH,
        ) { "batch size must be 1..${OutboxSchema.MAX_PURGE_BATCH}" }
    }

    override fun purge(): PurgeResult {
        val now = clock.instant()
        val outbox = deleteBatches(OutboxSchema.OUTBOX_PURGE_EXPIRED, now - retention.contentWindow)
        val inbox = deleteBatches(OutboxSchema.INBOX_PURGE_EXPIRED, now - retention.inboxRetention)
        val deadLetters = deleteBatches(OutboxSchema.DEAD_LETTER_PURGE_EXPIRED, now - retention.contentWindow)
        return PurgeResult(outbox, inbox, deadLetters)
    }

    /** Runs the purge function [function] in batches until a batch deletes fewer than the size. */
    private fun deleteBatches(
        function: String,
        cutoff: Instant,
    ): Long {
        var total = 0L
        do {
            // Each batch is its own transaction; the deletes of one call commit together.
            val deleted =
                scope.platformTransaction {
                    dsl
                        .select(JooqRoutines.purgeExpired(s, function, cutoff.atOffset(ZoneOffset.UTC), batchSize))
                        .fetchSingle()
                        .value1()
                }
            total += deleted
        } while (deleted == batchSize.toLong())
        return total
    }

    private companion object {
        const val BATCH = 1000
    }
}
