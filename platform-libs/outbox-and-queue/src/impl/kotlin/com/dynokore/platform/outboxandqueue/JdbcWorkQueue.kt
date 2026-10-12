package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * [WorkQueue] over the `work_queue` table of the service's schema ([QueueSchema]).
 *
 * - **Priority.** [claimNext] walks [Lane.entries] in order and serves a lane only when every earlier lane
 *   had nothing claimable. A lane whose own rate limit is used up is skipped, so it neither blocks nor
 *   is served: the lanes below it may use their own allowance (INV-REQ-1072). The allowance is shared by every
 *   replica of the service ([LaneRateLimiter], INV-Q-2966).
 * - **Partition order.** An item is claimable only if no earlier item of its (tenant, partition key, lane,
 *   consumer) is still in the table, claimed or not. Within a lane, a document's items are therefore
 *   handled one at a time in enqueue order; across lanes the priority applies, so a live item never waits for
 *   an earlier backfill item of the same document.
 * - **SKIP LOCKED.** Candidates are selected `FOR UPDATE SKIP LOCKED`, so workers never queue behind each other.
 *   A candidate row lock alone cannot keep a later item of a partition from being claimed while the head's
 *   claim is still uncommitted, so each claim also takes a transaction advisory lock on its partition and
 *   re-checks eligibility under it. All of this runs inside the `<svc>_sys`-owned definer function
 *   [QueueSchema.CLAIM_DUE], in one short transaction.
 * - **Cross-tenant claim (ARC 4.3 step 1a; INV-Q-3953).** The claim runs on [claimSource], a connection of the
 *   service's runtime login, which calls [QueueSchema.CLAIM_DUE] and gets the claim columns only; the payload is
 *   read later in the item's own tenant transaction.
 * - **Crash.** A claim not completed within [claimTimeout] is claimed again (at-least-once; the handler is
 *   idempotent, INV-REQ-1164).
 */
class JdbcWorkQueue(
    private val claimSource: DataSource,
    private val scope: TenantScope,
    schema: String,
    private val limiter: LaneRateLimiter,
    private val clock: Clock,
    private val consumer: String? = null,
    private val claimTimeout: Duration = Duration.ofMinutes(5),
) : WorkQueue {
    private val queue = "${SqlIdentifier.require(schema, "schema")}.${QueueSchema.WORK_QUEUE}"
    private val claimDue = "${SqlIdentifier.require(schema, "schema")}.${QueueSchema.CLAIM_DUE}"

    override fun enqueue(entry: QueueEntry): Long {
        val tenant = scope.tenants.current()
        return scope.connections
            .require()
            .prepareStatement(
                """
                insert into $queue (tenant_id, partition_key, lane, consumer, payload, enqueued_at)
                values (?, ?, ?, ?, ?::jsonb, ?) returning seq
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, tenant.value)
                statement.setString(2, entry.partitionKey)
                statement.setString(3, entry.lane.name)
                statement.setString(4, entry.consumer)
                statement.setString(5, entry.payloadJson)
                statement.setTimestamp(6, Timestamp.from(clock.instant()))
                statement.executeQuery().use {
                    check(it.next()) { "enqueue returned no row" }
                    it.getLong(1)
                }
            }
    }

    override fun claimNext(): ClaimedItem? {
        for (lane in Lane.entries) {
            val permit = limiter.acquire(lane) ?: continue
            val item = claimInLane(lane)
            if (item != null) return item
            limiter.refund(permit) // nothing claimable: no AP call will follow, so no permit is spent
        }
        return null
    }

    override fun payloadOf(item: ClaimedItem): String = scope.connections
        .require()
        .prepareStatement("select payload::text from $queue where seq = ?")
        .use { statement ->
            statement.setLong(1, item.seq)
            statement.executeQuery().use {
                check(it.next()) { "queue item ${item.seq} is not visible to this tenant" }
                it.getString(1)
            }
        }

    override fun complete(item: ClaimedItem) {
        scope.connections.require().prepareStatement("delete from $queue where seq = ?").use {
            it.setLong(1, item.seq)
            it.executeUpdate()
        }
    }

    override fun release(item: ClaimedItem) {
        scope.connections
            .require()
            .prepareStatement("update $queue set state = 'PENDING', claimed_at = null where seq = ?")
            .use {
                it.setLong(1, item.seq)
                it.executeUpdate()
            }
    }

    private fun claimInLane(lane: Lane): ClaimedItem? = claimSource.connection.use { connection ->
        val wasAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val claimed = claimDue(connection, lane)
            connection.commit() // ends the claim transaction and with it the partition lock
            claimed
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = wasAutoCommit
        }
    }

    private fun claimDue(
        connection: Connection,
        lane: Lane,
    ): ClaimedItem? = connection
        .prepareStatement("select claimed_seq, claimed_tenant, claimed_partition from $claimDue(?, ?, ?, ?)")
        .use { statement ->
            statement.setString(1, lane.name)
            statement.setString(2, consumer)
            statement.setTimestamp(3, Timestamp.from(clock.instant()))
            statement.setTimestamp(4, reclaimBefore())
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    ClaimedItem(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java)), rows.getString(3), lane)
                } else {
                    null
                }
            }
        }

    private fun reclaimBefore(): Timestamp = Timestamp.from(clock.instant().minus(claimTimeout))
}
