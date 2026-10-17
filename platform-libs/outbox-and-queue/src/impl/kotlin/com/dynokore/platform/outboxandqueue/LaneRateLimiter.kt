package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.OffsetDateTime

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
 * - **Access.** Each call is a jOOQ statement in its own transaction under the platform binding, on the runtime login
 *   (`SELECT, INSERT, UPDATE, DELETE` on the table; no RLS, as the rows hold no tenant data: CS-19 exception, as for
 *   `consumer_offset`; the table is `PLATFORM_SCOPED`).
 * - Every replica of a service is given the same [LaneLimits].
 */
class LaneRateLimiter(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val limits: LaneLimits,
) {
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val table: Table<*> = DSL.table(DSL.name(schemaName, QueueSchema.LANE_RATE_WINDOW))

    /** The stored row's own columns, qualified by the table name: in `ON CONFLICT DO UPDATE` a bare name is ambiguous. */
    private val storedStart =
        DSL.field(
            DSL.name(schemaName, QueueSchema.LANE_RATE_WINDOW, "window_start"),
            WINDOW_START.dataType,
        )
    private val storedUsed = DSL.field(DSL.name(schemaName, QueueSchema.LANE_RATE_WINDOW, "used"), USED.dataType)

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
        val ended = ended(storedStart, limit.window.toMillis())
        return scope.platformTransaction {
            dsl
                .insertInto(table, LANE, WINDOW_START, USED)
                .values(DSL.`val`(lane.name, SQLDataType.CLOB), now(), DSL.inline(1, SQLDataType.INTEGER))
                .onConflict(LANE)
                .doUpdate()
                .set(WINDOW_START, DSL.`when`(ended, now()).otherwise(storedStart))
                .set(USED, DSL.`when`(ended, DSL.inline(1, SQLDataType.INTEGER)).otherwise(storedUsed.plus(1)))
                .where(ended.or(storedUsed.lt(limit.permits)))
                .returning(WINDOW_START)
                .fetchOne()
                ?.let { LanePermit(lane, it.get(WINDOW_START)) }
        }
    }

    /**
     * Gives back a permit taken by [acquire] that turned out to have no work behind it. The permit returns to the
     * window it was taken from; once that window has ended there is nothing to give back.
     */
    fun refund(permit: LanePermit) {
        val start = permit.windowStart ?: return
        scope.platformTransaction {
            dsl
                .update(table)
                .set(USED, USED.minus(1))
                .where(LANE.eq(permit.lane.name).and(WINDOW_START.eq(start)).and(USED.gt(0)))
                .execute()
        }
    }

    /** Permits taken from [lane] in its current window by all replicas (0 for an unlimited lane). */
    fun used(lane: Lane): Int {
        val limit = limits.of(lane) ?: return 0
        return scope.platformTransaction {
            dsl
                .select(USED)
                .from(table)
                .where(LANE.eq(lane.name).and(DSL.not(ended(WINDOW_START, limit.window.toMillis()))))
                .fetchOne()
                ?.get(USED) ?: 0
        }
    }

    /** The stored window has ended by the database clock: `start + window <= now()`. */
    private fun ended(
        start: Field<OffsetDateTime>,
        windowMillis: Long,
    ): Condition = start.plus(window(windowMillis)).le(now())

    /** `make_interval(secs => window)`: the window as an interval, bound as seconds. */
    private fun window(millis: Long): Field<*> =
        DSL.function(
            DSL.name("pg_catalog", "make_interval"),
            SQLDataType.INTERVALDAYTOSECOND,
            DSL.inline(0),
            DSL.inline(0),
            DSL.inline(0),
            DSL.inline(0),
            DSL.inline(0),
            DSL.inline(0),
            DSL.`val`(millis / MILLIS_PER_SECOND, SQLDataType.DOUBLE),
        )

    /** The database clock: every replica sees the same window whatever its own clock says. */
    private fun now(): Field<OffsetDateTime> =
        DSL.function(DSL.name("pg_catalog", "now"), SQLDataType.TIMESTAMPWITHTIMEZONE)

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        val LANE = DSL.field(DSL.name("lane"), SQLDataType.CLOB)
        val WINDOW_START = DSL.field(DSL.name("window_start"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val USED = DSL.field(DSL.name("used"), SQLDataType.INTEGER)
    }
}

/** One permit of [lane], taken in the window starting at [windowStart] (null for an unlimited lane). */
class LanePermit internal constructor(
    val lane: Lane,
    internal val windowStart: OffsetDateTime?,
)
