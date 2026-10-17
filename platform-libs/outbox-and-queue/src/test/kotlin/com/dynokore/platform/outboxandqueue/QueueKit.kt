package com.dynokore.platform.outboxandqueue

import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test moves by hand, so rate-limit windows and lags are exact (no wall-clock time). */
class MutableClock(
    private var now: Instant = Instant.parse("2026-10-01T00:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** The `submission-svc` fixture's queue side: a worker's [JdbcWorkQueue] and the tenant transactions around it. */
class QueueKit(
    val harness: OutboxHarness,
    val clock: MutableClock = MutableClock(),
    limits: LaneLimits = LaneLimits(),
    consumer: String? = null,
) {
    val kit = harness.submission
    val scope = kit.scope
    val limiter = LaneRateLimiter(scope, kit.dsl, "submission", limits)
    val queue = JdbcWorkQueue(scope, kit.dsl, "submission", limiter, clock, consumer)

    fun enqueue(
        tenant: UUID,
        partition: String,
        lane: Lane,
        payload: String = """{"n":1}""",
    ): Long = kit.inTx(tenant) { queue.enqueue(QueueEntry(partition, lane, payload)) }

    /** [endRateWindows] of this kit's harness. */
    fun endRateWindows() = endRateWindows(harness)

    companion object {
        /**
         * Ends every lane's current rate-limit window. The windows run on the database clock, which the test cannot
         * move, so it moves the stored window starts back by a day instead.
         */
        fun endRateWindows(harness: OutboxHarness) {
            JooqKit.Access.runtime(harness.fixture, QueueSchema.jooqTables("submission")).single().asPlatform { dsl ->
                dsl
                    .update(DSL.table(DSL.name("submission", QueueSchema.LANE_RATE_WINDOW)))
                    .set(WINDOW_START, WINDOW_START.minus(DSL.`val`(DayToSecond(1), SQLDataType.INTERVALDAYTOSECOND)))
                    .execute()
            }
        }
    }

    /** Claims, completes and returns the lane and partition of each item, until nothing is claimable. */
    fun drain(): List<ClaimedItem> =
        buildList {
            while (true) {
                val item = queue.claimNext() ?: break
                kit.inTx(item.tenant.value) { queue.complete(item) }
                add(item)
            }
        }
}

private val WINDOW_START = DSL.field(DSL.name("window_start"), SQLDataType.TIMESTAMPWITHTIMEZONE)

/** The value, failing the test when it is null (JUnit's own `assertNotNull` returns nothing). */
fun <T : Any> notNull(value: T?): T = checkNotNull(value) { "expected a value" }
