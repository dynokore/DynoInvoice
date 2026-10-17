package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Per-lane rate limits shared across replicas (INV-Q-2966, INV-REQ-1072, INV-AC-2097, INV-AC-2104): three limiter
 * instances (three replicas of `submission-svc`) against one database, acquiring concurrently. Each lane's limit holds
 * for all of them together, and the background lanes draw only on their own allowance, never on live's.
 */
@Tag("integration")
class SharedRateLimitIT {
    private val tenant = ServiceKit.TENANT

    private val limits =
        LaneLimits(
            mapOf(
                Lane.LIVE to RateLimit(100, Duration.ofHours(1)),
                Lane.REPROCESSING to RateLimit(7, Duration.ofHours(1)),
                Lane.BACKFILL to RateLimit(4, Duration.ofHours(1)),
            ),
        )

    private val minuteLimits =
        LaneLimits(
            mapOf(
                Lane.LIVE to RateLimit(100, Duration.ofMinutes(1)),
                Lane.REPROCESSING to RateLimit(7, Duration.ofMinutes(1)),
                Lane.BACKFILL to RateLimit(4, Duration.ofMinutes(1)),
            ),
        )

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `three replicas together take at most each lane's own limit per window`(image: String) {
        OutboxHarness(image).use { harness ->
            val replicas =
                List(
                    REPLICAS,
                ) { LaneRateLimiter(harness.submission.scope, harness.submission.dsl, "submission", limits) }

            fun burst(): Map<Lane, Int> {
                val granted = ConcurrentLinkedQueue<Lane>()
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(REPLICAS * THREADS)
                try {
                    val tasks =
                        replicas.flatMap { limiter ->
                            List(THREADS) {
                                Callable {
                                    start.await()
                                    repeat(ATTEMPTS) {
                                        for (lane in listOf(Lane.REPROCESSING, Lane.BACKFILL)) {
                                            limiter.acquire(lane)?.let { granted.add(it.lane) }
                                        }
                                    }
                                }
                            }
                        }
                    val futures = tasks.map { pool.submit(it) }
                    start.countDown()
                    futures.forEach { it.get() }
                } finally {
                    pool.shutdown()
                }
                return granted.groupingBy { it }.eachCount()
            }

            val expected = mapOf(Lane.REPROCESSING to 7, Lane.BACKFILL to 4)
            assertEquals(expected, burst(), "one shared limit, not one per replica")
            replicas.forEach {
                assertEquals(7, it.used(Lane.REPROCESSING), "every replica sees the shared count")
                assertEquals(4, it.used(Lane.BACKFILL))
                assertEquals(0, it.used(Lane.LIVE), "no live allowance was spent")
                assertTrue(it.hasPermit(Lane.LIVE))
                assertFalse(it.hasPermit(Lane.REPROCESSING))
            }

            endWindows(harness)
            assertEquals(expected, burst(), "the next window has its own limit")

            endWindows(harness)
            assertNotNull(replicas[0].acquire(Lane.REPROCESSING))
            assertEquals(
                1,
                harness.count("select count(*) from submission.lane_rate_window where lane = 'REPROCESSING'"),
                "opening a window replaces the ended window's row",
            )
            assertEquals(1, replicas[1].used(Lane.REPROCESSING))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `on three replicas reprocessing and backfill draw only their own shared limit`(image: String) {
        OutboxHarness(image).use { harness ->
            // Three replicas whose own clocks disagree by two minutes either way (two 1-minute windows, within the
            // 5-minute claim timeout): the windows run on the database clock, so the skew cannot raise a lane's limit.
            val replicas = SKEW.map { QueueKit(harness, MutableClock(BASE.plus(it)), minuteLimits) }
            val q = replicas[0]
            repeat(20) { q.enqueue(tenant, "old-$it", Lane.REPROCESSING) }
            repeat(20) { q.enqueue(tenant, "back-$it", Lane.BACKFILL) }
            repeat(12) { q.enqueue(tenant, "live-$it", Lane.LIVE) }

            val claimed = ConcurrentLinkedQueue<ClaimedItem>()
            val pool = Executors.newFixedThreadPool(REPLICAS)
            try {
                replicas.map { kit -> pool.submit(Callable { claimed.addAll(kit.drain()) }) }.forEach { it.get() }
            } finally {
                pool.shutdown()
            }

            val byLane = claimed.groupingBy { it.lane }.eachCount()
            assertEquals(mapOf(Lane.LIVE to 12, Lane.REPROCESSING to 7, Lane.BACKFILL to 4), byLane)
            assertEquals(12, q.limiter.used(Lane.LIVE), "live spent only its own allowance")
            assertEquals(7, q.limiter.used(Lane.REPROCESSING), "every reprocessing claim counted against reprocessing")
            assertEquals(4, q.limiter.used(Lane.BACKFILL), "every backfill claim counted against backfill")
            replicas.forEach { assertNull(it.queue.claimNext(), "background lanes are used up on every replica") }

            repeat(3) { q.enqueue(tenant, "live-late-$it", Lane.LIVE) }
            val late = replicas[2].drain().map { it.lane }
            assertEquals(List(3) { Lane.LIVE }, late, "live never waits on a background limit")
        }
    }

    private fun endWindows(harness: OutboxHarness) = QueueKit.endRateWindows(harness)

    private companion object {
        val BASE: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val SKEW: List<Duration> = listOf(Duration.ZERO, Duration.ofMinutes(2), Duration.ofMinutes(-2))

        const val REPLICAS = 3
        const val THREADS = 4
        const val ATTEMPTS = 10
    }
}
