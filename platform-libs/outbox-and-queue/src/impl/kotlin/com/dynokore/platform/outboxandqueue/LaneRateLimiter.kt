package com.dynokore.platform.outboxandqueue

import java.sql.Connection
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * One fixed-window counter per lane, shared by every replica of a service (INV-Q-2966, INV-REQ-1072): the count is the
 * lane's row in the `lane_rate_window` table ([QueueSchema]) of the service's schema, so N replicas together take at
 * most [RateLimit.permits] per window, not N times that. The lanes share nothing: [acquire] on one lane never reads or
 * changes another's row, so reprocessing and backfill draw only on their own limit (INV-AC-2097, INV-AC-2104).
 *
 * - **One clock.** The window is chosen by the database clock (`now()`) inside the statement that takes the permit, so
 *   every replica sees the same window whatever its own clock says; replica clock skew cannot raise the limit.
 * - **Windows.** A lane's window opens at its first permit and lasts [RateLimit.window]; the first permit after it ends
 *   opens the next one.
 * - **Atomic.** A permit is one `INSERT ... ON CONFLICT DO UPDATE`, committed on its own: it either opens a new window
 *   (when the stored one has ended) or adds one to the current window while `used < permits`. Concurrent acquirers on
 *   any replica serialise on the lane's row, so the limit holds exactly.
 * - **Retention.** Opening a window replaces the lane's row of the ended window in the same statement, so at most one
 *   row per lane exists and nothing accumulates.
 * - **Access.** [dataSource] connects as the runtime login (`SELECT, INSERT, UPDATE, DELETE` on the table; no RLS, as
 *   the rows hold no tenant data: CS-19 exception, as for `consumer_offset`).
 * - Every replica of a service is given the same [LaneLimits].
 */
class LaneRateLimiter(
    private val dataSource: DataSource,
    schema: String,
    private val limits: LaneLimits,
) {
    private val table = "${SqlIdentifier.require(schema, "schema")}.${QueueSchema.LANE_RATE_WINDOW}"

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
        return withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    insert into $table as w (lane, window_start, used) values (?, now(), 1)
                    on conflict (lane) do update set
                        window_start = case when $ENDED then now() else w.window_start end,
                        used = case when $ENDED then 1 else w.used + 1 end
                    where $ENDED or w.used < ?
                    returning window_start
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, lane.name)
                    repeat(ENDED_PARAMS) { statement.setLong(2 + it, limit.window.toMillis()) }
                    statement.setInt(2 + ENDED_PARAMS, limit.permits)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) LanePermit(lane, rows.getObject(1, OffsetDateTime::class.java)) else null
                    }
                }
        }
    }

    /**
     * Gives back a permit taken by [acquire] that turned out to have no work behind it. The permit returns to the
     * window it was taken from; once that window has ended there is nothing to give back.
     */
    fun refund(permit: LanePermit) {
        val start = permit.windowStart ?: return
        withConnection { connection ->
            connection
                .prepareStatement("update $table set used = used - 1 where lane = ? and window_start = ? and used > 0")
                .use {
                    it.setString(1, permit.lane.name)
                    it.setObject(2, start)
                    it.executeUpdate()
                }
        }
    }

    /** Permits taken from [lane] in its current window by all replicas (0 for an unlimited lane). */
    fun used(lane: Lane): Int {
        val limit = limits.of(lane) ?: return 0
        return withConnection { connection ->
            connection
                .prepareStatement("select used from $table w where lane = ? and not ($ENDED)")
                .use { statement ->
                    statement.setString(1, lane.name)
                    statement.setLong(2, limit.window.toMillis())
                    statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
                }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = true
        block(connection)
    }

    private companion object {
        /** The stored window has ended by the database clock; its one `?` is the window in milliseconds. */
        const val ENDED = "w.window_start + ? * interval '1 millisecond' <= now()"
        const val ENDED_PARAMS = 3
    }
}

/** One permit of [lane], taken in the window starting at [windowStart] (null for an unlimited lane). */
class LanePermit internal constructor(
    val lane: Lane,
    internal val windowStart: OffsetDateTime?,
)
