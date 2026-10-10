package com.dynokore.platform.outboxandqueue

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
 *   is served: the lanes below it may use their own allowance (INV-REQ-1072).
 * - **Partition order.** An item is claimable only if no earlier item of its (tenant, partition key, lane,
 *   consumer) is still in the table, claimed or not. Within a lane, a document's items are therefore
 *   handled one at a time in enqueue order; across lanes the priority applies, so a live item never waits for
 *   an earlier backfill item of the same document.
 * - **SKIP LOCKED.** Candidates are selected `FOR UPDATE SKIP LOCKED`, so workers never queue behind each other.
 *   A candidate row lock alone cannot keep a later item of a partition from being claimed while the head's
 *   claim is still uncommitted, so each claim also takes a transaction advisory lock on its partition and
 *   re-checks eligibility under it.
 * - **Cross-tenant claim (ARC 4.3 step 1a).** The claim runs on [claimSource], a worker-role connection that
 *   sees only the claim columns; the payload is read later in the item's own tenant transaction.
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

    /** Unclaimed (or abandoned) and the oldest of its partition in its lane; its one `?` is the reclaim time. */
    private val eligible =
        """
        (state = 'PENDING' or (state = 'CLAIMED' and claimed_at < ?))
        and not exists (
            select 1 from $queue p
            where p.tenant_id = q.tenant_id and p.partition_key = q.partition_key and p.lane = q.lane
              and p.consumer is not distinct from q.consumer and p.seq < q.seq)
        """.trimIndent()

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
            if (!limiter.tryAcquire(lane)) continue
            val item = claimInLane(lane)
            if (item != null) return item
            limiter.refund(lane) // nothing claimable: no AP call will follow, so no permit is spent
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
        connection.autoCommit = false
        try {
            val claimed = candidates(connection, lane).firstNotNullOfOrNull { claim(connection, it) }
            connection.commit()
            claimed
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }

    private fun candidates(
        connection: Connection,
        lane: Lane,
    ): List<Candidate> = connection
        .prepareStatement(
            """
            select seq, tenant_id, partition_key from $queue q
            where lane = ? and consumer is not distinct from ? and $eligible
            order by seq limit ? for update of q skip locked
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, lane.name)
            statement.setString(2, consumer)
            statement.setTimestamp(3, reclaimBefore())
            statement.setInt(4, CANDIDATES)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Candidate(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java)), rows.getString(3), lane))
                    }
                }
            }
        }

    /** Locks the candidate's partition, re-checks it is still the head and unclaimed, and claims it. */
    private fun claim(
        connection: Connection,
        candidate: Candidate,
    ): ClaimedItem? {
        if (!lockPartition(connection, candidate)) return null
        val stillEligible =
            connection
                .prepareStatement("select 1 from $queue q where seq = ? and $eligible for update of q skip locked")
                .use { statement ->
                    statement.setLong(1, candidate.seq)
                    statement.setTimestamp(2, reclaimBefore())
                    statement.executeQuery().use { it.next() }
                }
        if (!stillEligible) return null
        connection
            .prepareStatement("update $queue set state = 'CLAIMED', claimed_at = ?, attempts = attempts + 1 where seq = ?")
            .use {
                it.setTimestamp(1, Timestamp.from(clock.instant()))
                it.setLong(2, candidate.seq)
                it.executeUpdate()
            }
        return ClaimedItem(candidate.seq, candidate.tenant, candidate.partitionKey, candidate.lane)
    }

    private fun lockPartition(
        connection: Connection,
        candidate: Candidate,
    ): Boolean = connection.prepareStatement("select pg_try_advisory_xact_lock(hashtextextended(?, 0))").use {
        it.setString(1, "$queue|${candidate.tenant}|${candidate.partitionKey}|${candidate.lane}|$consumer")
        it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }

    private fun reclaimBefore(): Timestamp = Timestamp.from(clock.instant().minus(claimTimeout))

    private class Candidate(
        val seq: Long,
        val tenant: TenantId,
        val partitionKey: String,
        val lane: Lane,
    )

    private companion object {
        const val CANDIDATES = 20
    }
}
