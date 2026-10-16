package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.tenancykernel.LaneRefusedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Permit accounting of [LanePermits] (INV-REQ-1230 b1; owner ruling INV-Q-4502 (A)), JDBC-free: a permit is given back
 * when released, a permit released twice gives back one, a lane with no permits is refused and a saturated lane times out
 * after the pool's connection timeout. Before and after: the class was `LanedDataSourceTest` over `LanedDataSource` and a
 * mocked pool; its three checks on the pooled connection (a failed checkout, a double close, a failed close) went with
 * `LanedDataSource` and have no replacement yet (reported to the coordinator as a coverage gap); the permit checks
 * below keep their compared values.
 */
class LanedDataSourceTest {
    private val budgets = LaneBudgets(2, 1, 0)
    private val gate = LanePermits(budgets, TIMEOUT_MS)

    @Test
    fun `releasing a permit twice gives back one permit`() {
        val first = gate.acquire()
        val second = gate.acquire()
        gate.availablePermits(PoolLane.REQUEST) shouldBe 0
        first.release()
        first.release()
        gate.availablePermits(PoolLane.REQUEST) shouldBe 1
        second.release()
        gate.availablePermits(PoolLane.REQUEST) shouldBe budgets.request
    }

    @Test
    fun `a lane with a budget of 0 is refused and a saturated lane times out`() {
        val refused = shouldThrow<LaneRefusedException> { PoolLanes.inLane(PoolLane.REPROCESSING) { gate.acquire() } }
        refused.message shouldBe
            "the REPROCESSING lane has no permits (budget 0) and never runs on another lane (INV-REQ-1072 b5)"
        val held = PoolLanes.inLane(PoolLane.BACKGROUND) { gate.acquire() }
        val timedOut = shouldThrow<LaneRefusedException> { PoolLanes.inLane(PoolLane.BACKGROUND) { gate.acquire() } }
        timedOut.message shouldBe "the BACKGROUND lane's 1 permits stayed in use for $TIMEOUT_MS ms"
        timedOut.interrupted shouldBe false
        held.release()
        gate.availablePermits(PoolLane.BACKGROUND) shouldBe 1
    }

    @Test
    fun `a thread interrupted while it waits for a permit keeps its interrupt and takes no permit`() {
        val held = PoolLanes.inLane(PoolLane.BACKGROUND) { gate.acquire() }
        val waiting = CountDownLatch(1)
        val result = CompletableFuture<Pair<Throwable?, Boolean>>()
        val waiter =
            Thread.ofPlatform().start {
                waiting.countDown()
                val failure = runCatching { PoolLanes.inLane(PoolLane.BACKGROUND) { gate.acquire() } }.exceptionOrNull()
                result.complete(failure to Thread.currentThread().isInterrupted)
            }
        waiting.await()
        Thread.sleep(SETTLE_MS)
        waiter.interrupt()
        val (failure, interrupted) = result.get(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        (failure is LaneRefusedException && failure.interrupted && failure.cause is InterruptedException) shouldBe true
        interrupted shouldBe true
        gate.availablePermits(PoolLane.BACKGROUND) shouldBe 0
        held.release()
        gate.availablePermits(PoolLane.BACKGROUND) shouldBe 1
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
        const val SETTLE_MS = 200L
    }
}
