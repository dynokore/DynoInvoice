package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The partition-ordered queue with SKIP LOCKED and the priority lanes (INV-REQ-540, INV-REQ-1072). The "load tests" of
 * INV-AC-2097 and INV-AC-2104 are controlled workloads on an injected clock: they assert the order items are
 * dequeued in and how many permits each lane's own limit spent, never throughput or elapsed time.
 */
@Tag("integration")
class WorkQueueIT {
    private val tenant = ServiceKit.TENANT

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a partition is claimed one item at a time in order and other partitions are not blocked`(image: String) {
        OutboxHarness(image).use { harness ->
            val a = QueueKit(harness)
            val b = QueueKit(harness) // a second worker
            val docA = "doc-a"
            val docB = "doc-b"
            val a1 = a.enqueue(tenant, docA, Lane.LIVE)
            val a2 = a.enqueue(tenant, docA, Lane.LIVE)
            val b1 = a.enqueue(tenant, docB, Lane.LIVE)

            val first = notNull(a.queue.claimNext())
            val second = notNull(b.queue.claimNext())
            assertEquals(a1, first.seq)
            assertEquals(b1, second.seq, "the second worker takes doc-b, not doc-a's second item")
            assertNull(b.queue.claimNext(), "doc-a's second item waits for its first")

            a.kit.inTx(tenant) { a.queue.complete(first) }
            assertEquals(a2, notNull(b.queue.claimNext()).seq)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `concurrent workers claim every item once and each partition in enqueue order`(image: String) {
        OutboxHarness(image).use { harness ->
            val producer = QueueKit(harness)
            val partitions = 12
            val perPartition = 5
            val seqs = HashMap<String, MutableList<Long>>()
            repeat(perPartition) { round ->
                repeat(partitions) { p ->
                    val seq = producer.enqueue(tenant, "doc-$p", Lane.LIVE, """{"r":$round}""")
                    seqs.getOrPut("doc-$p") { mutableListOf() } += seq
                }
            }
            val done = ConcurrentLinkedQueue<Pair<String, Long>>()
            val pool = Executors.newFixedThreadPool(4)
            try {
                val tasks = (1..4).map { Callable { work(harness, done, partitions * perPartition) } }
                pool.invokeAll(tasks).forEach { it.get() }
            } finally {
                pool.shutdown()
            }
            assertEquals(partitions * perPartition, done.size)
            assertEquals(done.size, done.map { it.second }.toSet().size, "no item was claimed twice")
            seqs.forEach { (partition, expected) ->
                assertEquals(expected, done.filter { it.first == partition }.map { it.second }, "order of $partition")
            }
            assertEquals(0, harness.count("select count(*) from submission.work_queue"))
        }
    }

    /** One worker: claims and completes until [total] items are done overall (bounded idle polling). */
    private fun work(
        harness: OutboxHarness,
        done: ConcurrentLinkedQueue<Pair<String, Long>>,
        total: Int,
    ) {
        val worker = QueueKit(harness)
        var idle = 0
        while (idle < MAX_IDLE && done.size < total) {
            val item = worker.queue.claimNext()
            if (item == null) {
                idle++
            } else {
                worker.kit.inTx(tenant) { worker.queue.complete(item) }
                done += item.partitionKey to item.seq
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `reprocessing and backfill never dequeue ahead of live work (INV-AC-2097, INV-AC-2104)`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            repeat(3) { q.enqueue(tenant, "old-$it", Lane.REPROCESSING) }
            repeat(3) { q.enqueue(tenant, "back-$it", Lane.BACKFILL) }
            repeat(3) { q.enqueue(tenant, "live-$it", Lane.LIVE) }
            q.enqueue(tenant, "due-0", Lane.DEADLINE_NEAR)

            val firstTwo =
                (1..2).map {
                    notNull(q.queue.claimNext()).also { item -> q.kit.inTx(tenant) { q.queue.complete(item) } }
                }
            // Live work arrives while reprocessing and backfill are still queued.
            q.enqueue(tenant, "live-late", Lane.LIVE)
            val rest = q.drain()

            val order = (firstTwo + rest).map { it.lane }
            assertEquals(listOf(Lane.DEADLINE_NEAR, Lane.LIVE), order.take(2))
            val lastLive = order.indexOfLast { it == Lane.LIVE || it == Lane.DEADLINE_NEAR }
            val firstBackground = order.indexOfFirst { it == Lane.REPROCESSING || it == Lane.BACKFILL }
            assertTrue(lastLive < firstBackground, "no live item waited behind a background item: $order")
            assertEquals(
                List(3) { Lane.REPROCESSING } + List(3) { Lane.BACKFILL },
                order.drop(lastLive + 1),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `each lane draws only on its own rate limit`(image: String) {
        OutboxHarness(image).use { harness ->
            val limits =
                LaneLimits(
                    mapOf(
                        Lane.LIVE to RateLimit(100, Duration.ofMinutes(1)),
                        Lane.REPROCESSING to RateLimit(2, Duration.ofMinutes(1)),
                        Lane.BACKFILL to RateLimit(1, Duration.ofMinutes(1)),
                    ),
                )
            val q = QueueKit(harness, limits = limits)
            repeat(5) { q.enqueue(tenant, "old-$it", Lane.REPROCESSING) }
            repeat(5) { q.enqueue(tenant, "back-$it", Lane.BACKFILL) }

            val window1 = q.drain().map { it.lane }
            assertEquals(listOf(Lane.REPROCESSING, Lane.REPROCESSING, Lane.BACKFILL), window1, "own limits only")
            assertEquals(0, q.limiter.used(Lane.LIVE), "no live allowance was spent")
            assertEquals(2, q.limiter.used(Lane.REPROCESSING))
            assertEquals(1, q.limiter.used(Lane.BACKFILL))
            assertNull(q.queue.claimNext(), "both background lanes are used up for this window")

            q.clock.advance(Duration.ofMinutes(1))
            val window2 = q.drain().map { it.lane }
            assertEquals(listOf(Lane.REPROCESSING, Lane.REPROCESSING, Lane.BACKFILL), window2)

            // Live work in the same window is untouched by the background lanes' limits.
            repeat(3) { q.enqueue(tenant, "live-$it", Lane.LIVE) }
            assertEquals(List(3) { Lane.LIVE }, q.drain().map { it.lane })
            assertEquals(3, q.limiter.used(Lane.LIVE))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a lane out of permits is skipped without blocking the lanes below`(image: String) {
        OutboxHarness(image).use { harness ->
            val limits = LaneLimits(mapOf(Lane.LIVE to RateLimit(1, Duration.ofMinutes(1))))
            val q = QueueKit(harness, limits = limits)
            assertNull(q.queue.claimNext())
            assertEquals(0, q.limiter.used(Lane.LIVE), "an empty claim refunds its permit")
            q.enqueue(tenant, "live-0", Lane.LIVE)
            q.enqueue(tenant, "live-1", Lane.LIVE)
            q.enqueue(tenant, "old-0", Lane.REPROCESSING)
            val lanes = q.drain().map { it.lane }
            assertEquals(listOf(Lane.LIVE, Lane.REPROCESSING), lanes, "live-1 waits on live's own limit only")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a released item is claimed again first and an abandoned claim is reclaimed`(image: String) {
        OutboxHarness(image).use { harness ->
            val q = QueueKit(harness)
            val first = q.enqueue(tenant, "doc", Lane.LIVE)
            val second = q.enqueue(tenant, "doc", Lane.LIVE)
            val item = notNull(q.queue.claimNext())
            q.kit.inTx(tenant) { q.queue.release(item) }
            assertEquals(first, notNull(q.queue.claimNext()).seq)
            assertNull(q.queue.claimNext(), "claimed and not finished: the partition waits")
            q.clock.advance(Duration.ofMinutes(6))
            assertEquals(first, notNull(q.queue.claimNext()).seq, "abandoned claim is reclaimed after the timeout")
            assertEquals(3, harness.count("select attempts from submission.work_queue where seq = ?", first))
            assertEquals(1, harness.count("select count(*) from submission.work_queue where seq = ?", second))
        }
    }

    private companion object {
        const val MAX_IDLE = 200
    }
}
