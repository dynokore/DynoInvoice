package com.dynokore.platform.serviceruntime.pool

import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Permit accounting of [LanedDataSource] (INV-REQ-1230 b1): a permit is given back when the pool fails to hand out a
 * connection and when a thread is interrupted while it waits, and closing a connection twice gives back one permit.
 */
class LanedDataSourceTest {
    private val pool = mockk<HikariDataSource>(relaxed = true)
    private val budgets = LaneBudgets(2, 1, 0)

    init {
        every { pool.maximumPoolSize } returns budgets.maximum
        every { pool.connectionTimeout } returns TIMEOUT_MS
    }

    private val lanes = LanedDataSource(pool, budgets)

    @Test
    fun `a checkout the pool fails gives its permit back`() {
        every { pool.connection } throws SQLTransientConnectionException("pool timeout")
        repeat(budgets.request + 1) { shouldThrow<SQLTransientConnectionException> { lanes.connection } }
        lanes.availablePermits(PoolLane.REQUEST) shouldBe budgets.request
    }

    @Test
    fun `closing a connection twice gives back one permit and closes the pooled connection once per call`() {
        val pooled = mockk<Connection>(relaxed = true)
        every { pool.connection } returns pooled
        val first = lanes.connection
        val second = lanes.connection
        lanes.availablePermits(PoolLane.REQUEST) shouldBe 0
        first.close()
        first.close()
        lanes.availablePermits(PoolLane.REQUEST) shouldBe 1
        second.close()
        lanes.availablePermits(PoolLane.REQUEST) shouldBe 2
        verify(exactly = 3) { pooled.close() }
    }

    @Test
    fun `a close the pool fails still gives the permit back once`() {
        val pooled = mockk<Connection>(relaxed = true)
        every { pool.connection } returns pooled
        every { pooled.close() } throws SQLException("broken")
        val connection = PoolLanes.inLane(PoolLane.BACKGROUND) { lanes.connection }
        shouldThrow<SQLException> { connection.close() }
        shouldThrow<SQLException> { connection.close() }
        lanes.availablePermits(PoolLane.BACKGROUND) shouldBe 1
    }

    @Test
    fun `a thread interrupted while it waits for a permit keeps its interrupt and takes no permit`() {
        every { pool.connection } returns mockk(relaxed = true)
        val held = PoolLanes.inLane(PoolLane.BACKGROUND) { lanes.connection }
        val waiting = CountDownLatch(1)
        val result = CompletableFuture<Pair<Throwable?, Boolean>>()
        val waiter =
            Thread.ofPlatform().start {
                waiting.countDown()
                val failure = runCatching { PoolLanes.inLane(PoolLane.BACKGROUND) { lanes.connection } }.exceptionOrNull()
                result.complete(failure to Thread.currentThread().isInterrupted)
            }
        waiting.await()
        Thread.sleep(SETTLE_MS)
        waiter.interrupt()
        val (failure, interrupted) = result.get(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        (failure is SQLException && failure.cause is InterruptedException) shouldBe true
        interrupted shouldBe true
        lanes.availablePermits(PoolLane.BACKGROUND) shouldBe 0
        held.close()
        lanes.availablePermits(PoolLane.BACKGROUND) shouldBe 1
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 200L
    }
}
