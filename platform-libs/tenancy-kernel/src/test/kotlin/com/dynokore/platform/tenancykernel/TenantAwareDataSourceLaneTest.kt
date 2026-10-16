package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lane permit of [TenantAwareDataSource] is released exactly once per permit taken (INV-Q-4502 (A)), driven only
 * through [TestPoolFixture] and [TransactionRunner] against a real pool. Double close and failed close are in
 * [TenantAwareDataSourceConnectionTest].
 */
@Tag("integration")
class TenantAwareDataSourceLaneTest {
    private class CountingGate : LaneGate {
        val acquired = AtomicInteger()
        val released = AtomicInteger()

        override fun acquire(): LanePermit {
            acquired.incrementAndGet()
            return LanePermit { released.incrementAndGet() }
        }
    }

    private fun <T> withRunner(
        gate: CountingGate,
        maximumPoolSize: Int = 1,
        timeoutMillis: Long = 500,
        body: (tenants: TenantContext, runner: TransactionRunner) -> T,
    ): T =
        PostgresProbeFixture("postgres:18").use { fixture ->
            TestPoolFixture(
                fixture.jdbcUrl(),
                PostgresProbeFixture.APP,
                PostgresProbeFixture.APP_PASSWORD,
                maximumPoolSize = maximumPoolSize,
                connectionTimeoutMillis = timeoutMillis,
            ).use { pool ->
                val tenants = TenantContextImpl()
                body(tenants, pool.runner(tenants, gate))
            }
        }

    private fun <T> TenantContext.asTenant(block: () -> T): T = bind(TenantId(UUID.randomUUID()), "test-actor", block)

    @Test
    fun `a finished transaction releases the permit once`() {
        val gate = CountingGate()
        withRunner(gate) { tenants, runner -> tenants.asTenant { runner.inTransaction { 1 } } }
        assertEquals(1, gate.acquired.get())
        assertEquals(1, gate.released.get())
    }

    @Test
    fun `a failing transaction releases the permit once`() {
        val gate = CountingGate()
        withRunner(gate) { tenants, runner ->
            assertThrows(IllegalStateException::class.java) {
                tenants.asTenant { runner.inTransaction { error("rolled back") } }
            }
        }
        assertEquals(1, gate.acquired.get())
        assertEquals(1, gate.released.get())
    }

    @Test
    fun `a checkout the pool fails gives its permit back once`() {
        val gate = CountingGate()
        val executor = Executors.newSingleThreadExecutor()
        try {
            withRunner(gate) { tenants, runner ->
                val holding = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder =
                    executor.submit {
                        tenants.asTenant {
                            runner.inTransaction {
                                holding.countDown()
                                release.await(10, TimeUnit.SECONDS)
                            }
                        }
                    }
                assertEquals(true, holding.await(10, TimeUnit.SECONDS))
                // the pool's one connection is taken: this checkout times out in the pool, after the permit was taken
                val failure = runCatching { tenants.asTenant { runner.inTransaction { 1 } } }.exceptionOrNull()
                assertInstanceOf(Exception::class.java, failure)
                assertEquals(2, gate.acquired.get())
                assertEquals(1, gate.released.get())
                release.countDown()
                holder.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(2, gate.acquired.get())
        assertEquals(2, gate.released.get())
    }
}
