package com.dynokore.platform.outboxandqueue

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
    val limiter = LaneRateLimiter(harness.fixture.workerDataSource(), "submission", limits, clock)
    val scope = TenantScope(kit.tenants, kit.boundary, kit.boundary)
    val queue = JdbcWorkQueue(harness.fixture.workerDataSource(), scope, "submission", limiter, clock, consumer)

    fun enqueue(
        tenant: UUID,
        partition: String,
        lane: Lane,
        payload: String = """{"n":1}""",
    ): Long = kit.inTx(tenant) { queue.enqueue(QueueEntry(partition, lane, payload)) }

    /** Claims, completes and returns the lane and partition of each item, until nothing is claimable. */
    fun drain(): List<ClaimedItem> = buildList {
        while (true) {
            val item = queue.claimNext() ?: break
            kit.inTx(item.tenant.value) { queue.complete(item) }
            add(item)
        }
    }
}

/** The value, failing the test when it is null (JUnit's own `assertNotNull` returns nothing). */
fun <T : Any> notNull(value: T?): T = checkNotNull(value) { "expected a value" }
