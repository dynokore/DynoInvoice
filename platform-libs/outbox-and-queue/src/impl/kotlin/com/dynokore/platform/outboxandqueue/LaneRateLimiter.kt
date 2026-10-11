package com.dynokore.platform.outboxandqueue

import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * One fixed-window counter per lane, shared by every replica of a service (INV-Q-2966, INV-REQ-1072): the count is a
 * row of the `lane_rate_window` table ([QueueSchema]) in the service's schema, so N replicas together take at most
 * [RateLimit.permits] per window, not N times that. The lanes share nothing: [acquire] on one lane never reads or
 * changes another's row, so reprocessing and backfill draw only on their own limit (INV-AC-2097, INV-AC-2104).
 *
 * - **Windows.** A lane's windows are aligned to the epoch (`floor(now / window) * window`), so every replica derives
 *   the same window from [clock] without coordinating. Every replica of a service is given the same [LaneLimits].
 * - **Atomic.** A permit is one `INSERT ... ON CONFLICT DO UPDATE ... WHERE used < permits`, committed on its own;
 *   concurrent acquirers on any replica serialise on the row, so the limit holds exactly.
 * - **Retention.** When a replica sees a lane's window advance it deletes that lane's rows older than the previous
 *   window, so at most two rows per lane remain. The previous window is kept so that a replica whose clock lags by less
 *   than one window adds to the existing count rather than re-creating a deleted row at zero.
 * - **Access.** [dataSource] connects as the worker role (`SELECT, INSERT, UPDATE, DELETE` on the table; no RLS, as
 *   the rows hold no tenant data: CS-19 exception, as for `consumer_offset`).
 */
class LaneRateLimiter(
    private val dataSource: DataSource,
    schema: String,
    private val limits: LaneLimits,
    private val clock: Clock,
) {
    private val table = "${SqlIdentifier.require(schema, "schema")}.${QueueSchema.LANE_RATE_WINDOW}"
    private val seenWindow = ConcurrentHashMap<Lane, Instant>()

    init {
        Lane.entries.forEach { lane ->
            limits.of(lane)?.let { require(it.window.toMillis() >= 1) { "window of $lane must be at least 1 ms" } }
        }
    }

    /** Whether [lane] has a permit left now, without taking it. */
    fun hasPermit(lane: Lane): Boolean {
        val limit = limits.of(lane) ?: return true
        return used(lane) < limit.permits
    }

    /** Takes one permit of [lane]; null when its current window is used up on all replicas together. */
    fun acquire(lane: Lane): LanePermit? {
        val limit = limits.of(lane) ?: return LanePermit(lane, null)
        val start = windowStart(limit)
        return withConnection { connection ->
            advance(connection, lane, limit, start)
            val taken =
                connection
                    .prepareStatement(
                        """
                        insert into $table as w (lane, window_start, used) values (?, ?, 1)
                        on conflict (lane, window_start) do update set used = w.used + 1 where w.used < ?
                        returning used
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, lane.name)
                        statement.setTimestamp(2, Timestamp.from(start))
                        statement.setInt(3, limit.permits)
                        statement.executeQuery().use { it.next() }
                    }
            if (taken) LanePermit(lane, start) else null
        }
    }

    /**
     * Gives back a permit taken by [acquire] that turned out to have no work behind it. The permit returns to the
     * window it was taken from; once that window has been deleted there is nothing to give back.
     */
    fun refund(permit: LanePermit) {
        val start = permit.windowStart ?: return
        withConnection { connection ->
            connection
                .prepareStatement("update $table set used = used - 1 where lane = ? and window_start = ? and used > 0")
                .use {
                    it.setString(1, permit.lane.name)
                    it.setTimestamp(2, Timestamp.from(start))
                    it.executeUpdate()
                }
        }
    }

    /** Permits taken from [lane] in its current window by all replicas (0 for an unlimited lane). */
    fun used(lane: Lane): Int {
        val limit = limits.of(lane) ?: return 0
        return withConnection { connection ->
            connection
                .prepareStatement("select used from $table where lane = ? and window_start = ?")
                .use { statement ->
                    statement.setString(1, lane.name)
                    statement.setTimestamp(2, Timestamp.from(windowStart(limit)))
                    statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
                }
        }
    }

    /** On the first acquire of a new window, deletes [lane]'s rows of windows that ended before the previous one. */
    private fun advance(
        connection: Connection,
        lane: Lane,
        limit: RateLimit,
        start: Instant,
    ) {
        if (seenWindow.put(lane, start) == start) return
        connection.prepareStatement("delete from $table where lane = ? and window_start < ?").use {
            it.setString(1, lane.name)
            it.setTimestamp(2, Timestamp.from(start.minus(limit.window)))
            it.executeUpdate()
        }
    }

    private fun windowStart(limit: RateLimit): Instant {
        val window = limit.window.toMillis()
        return Instant.ofEpochMilli(Math.floorDiv(clock.instant().toEpochMilli(), window) * window)
    }

    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = true
        block(connection)
    }
}

/** One permit of [lane], taken in the window starting at [windowStart] (null for an unlimited lane). */
class LanePermit internal constructor(
    val lane: Lane,
    internal val windowStart: Instant?,
)
