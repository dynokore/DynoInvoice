package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * The [TransactionRunner.outsideOpenTransaction] seam (owner answer INV-Q-5609 (b), ruling INV-Q-5683). The runner is
 * driven through a `Connection` stub that counts commit, rollback and close and issues no SQL statement of its own
 * (INV-Q-5327), behind [TenantAwareDataSource] with a counting [LaneGate]. One case uses a real pool.
 */
class TransactionRunnerSeamTest {
    private class Stub {
        var commits = 0
        var rollbacks = 0
        var closes = 0
        private var closed = false

        val connection: Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
                when (method.name) {
                    "isClosed" -> {
                        closed
                    }

                    "setAutoCommit" -> {
                        null
                    }

                    "prepareStatement" -> {
                        statement()
                    }

                    "createStatement" -> {
                        statement()
                    }

                    "commit" -> {
                        commits++
                        null
                    }

                    "rollback" -> {
                        rollbacks++
                        null
                    }

                    "close" -> {
                        closes++
                        closed = true
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as Connection

        private fun statement(): PreparedStatement =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
                when (method.name) {
                    "execute" -> false
                    "setString", "close" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as PreparedStatement
    }

    /** A gate with [limit] permits: taking more than that is refused like the real lane gate refuses. */
    private class CountingGate(
        private val limit: Int,
    ) : LaneGate {
        val acquired = AtomicInteger()
        val released = AtomicInteger()

        override fun acquire(): LanePermit {
            if (acquired.get() - released.get() >= limit) throw LaneRefusedException("lane full: limit $limit")
            acquired.incrementAndGet()
            return LanePermit { released.incrementAndGet() }
        }
    }

    private class Fixture(
        limit: Int = 100,
    ) {
        val stubs = mutableListOf<Stub>()
        val gate = CountingGate(limit)
        val tenants = TenantContextImpl()
        val dataSource: DataSource =
            TenantAwareDataSource(
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getConnection" -> Stub().also { stubs.add(it) }.connection
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as DataSource,
                gate,
            )
        val runner = TransactionRunnerImpl(dataSource, tenants, null, SignedBinding.keys())
        val tenantA = TenantId(UUID.randomUUID())
        val tenantB = TenantId(UUID.randomUUID())

        fun <T> asA(block: () -> T): T = tenants.bind(tenantA, "actor-a", block)

        fun <T> asB(block: () -> T): T = tenants.bind(tenantB, "actor-a", block)
    }

    private val refusal = "A nested transaction must run under the open transaction's binding"

    @Test
    fun `1 nested same binding without the seam joins`() {
        val f = Fixture()
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.inTransaction { assertSame(outer, f.runner.transactionToken()) }
            }
        }
        assertEquals(1, f.stubs.size)
    }

    @Test
    fun `2 nested different binding without the seam is still refused`() {
        val f = Fixture()
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                val refused =
                    assertThrows(
                        IllegalStateException::class.java,
                    ) { f.asB { f.runner.inTransaction { f.runner.transactionToken() } } }
                assertTrue(refused.message!!.startsWith(refusal))
                assertSame(outer, f.runner.transactionToken())
            }
        }
    }

    @Test
    fun `3 inside the seam a different binding opens its own connection`() {
        val f = Fixture()
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.outsideOpenTransaction {
                    assertNull(f.runner.transactionToken())
                    f.asB {
                        f.runner.inTransaction {
                            val inner = f.runner.transactionToken()
                            assertNotNull(inner)
                            assertNotSame(outer, inner)
                        }
                    }
                    assertEquals(2, f.gate.acquired.get())
                    assertEquals(1, f.gate.released.get())
                }
                assertEquals(1, f.gate.released.get())
            }
        }
        assertEquals(2, f.gate.released.get())
    }

    @Test
    fun `4 the outer connection stays open during the block`() {
        val f = Fixture()
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.outsideOpenTransaction {
                    val outerStub = f.stubs[0]
                    assertEquals(0, outerStub.commits)
                    assertEquals(0, outerStub.rollbacks)
                    assertEquals(0, outerStub.closes)
                }
                assertSame(outer, f.runner.transactionToken())
                f.runner.inTransaction { assertSame(outer, f.runner.transactionToken()) }
            }
        }
    }

    @Test
    fun `5 the outer transaction commits after the block and rolls back when the body throws`() {
        val f = Fixture()
        f.asA { f.runner.inTransaction { f.runner.outsideOpenTransaction { 1 } } }
        assertEquals(1, f.stubs[0].commits)
        assertEquals(0, f.stubs[0].rollbacks)
        assertEquals(1, f.stubs[0].closes)

        val g = Fixture()
        assertThrows(IllegalStateException::class.java) {
            g.asA {
                g.runner.inTransaction {
                    g.runner.outsideOpenTransaction { 1 }
                    error("after the block")
                }
            }
        }
        assertEquals(1, g.stubs[0].rollbacks)
        assertEquals(0, g.stubs[0].commits)
    }

    @Test
    fun `6 an exception in the seam block leaves the outer open`() {
        val f = Fixture()
        val failure = IllegalArgumentException("seam block")
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                val caught =
                    assertThrows(
                        IllegalArgumentException::class.java,
                    ) { f.runner.outsideOpenTransaction { throw failure } }
                assertSame(failure, caught)
                assertSame(outer, f.runner.transactionToken())
                assertEquals(0, f.stubs[0].rollbacks)
            }
        }
        assertEquals(1, f.stubs[0].commits)
    }

    @Test
    fun `7 an inner transaction under the same binding in the seam opens its own connection`() {
        val f = Fixture()
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.outsideOpenTransaction {
                    f.runner.inTransaction { assertNotSame(outer, f.runner.transactionToken()) }
                }
            }
        }
        assertEquals(2, f.stubs.size)
    }

    @Test
    fun `8 no transaction open runs the block and nested seams restore each level`() {
        val f = Fixture()
        assertEquals(5, f.runner.outsideOpenTransaction { 5 })
        assertNull(f.runner.transactionToken())
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.outsideOpenTransaction {
                    f.runner.inTransaction {
                        val middle = f.runner.transactionToken()
                        f.runner.outsideOpenTransaction {
                            assertNull(f.runner.transactionToken())
                            f.runner.inTransaction { assertNotSame(middle, f.runner.transactionToken()) }
                        }
                        assertSame(middle, f.runner.transactionToken())
                    }
                    assertNull(f.runner.transactionToken())
                }
                assertSame(outer, f.runner.transactionToken())
            }
        }
    }

    @Test
    fun `9 look-alikes the seam of another runner or thread clears nothing here`() {
        val a = Fixture()
        val b = TransactionRunnerImpl(a.dataSource, a.tenants, null, SignedBinding.keys())
        a.asA {
            a.runner.inTransaction {
                val token = a.runner.transactionToken()
                assertSame(token, b.outsideOpenTransaction { a.runner.transactionToken() })
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val holding = CountDownLatch(1)
            val seamDone = CountDownLatch(1)
            val seen = AtomicReference<Any?>()
            val expected = AtomicReference<Any?>()
            val other =
                executor.submit {
                    a.asA {
                        a.runner.inTransaction {
                            expected.set(a.runner.transactionToken())
                            holding.countDown()
                            seamDone.await(10, TimeUnit.SECONDS)
                            seen.set(a.runner.transactionToken())
                        }
                    }
                }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            a.runner.outsideOpenTransaction { 1 }
            seamDone.countDown()
            other.get(10, TimeUnit.SECONDS)
            assertNotNull(seen.get())
            assertSame(expected.get(), seen.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `10 the impl overrides the member`() {
        val f = Fixture()
        val runner: TransactionRunner = f.runner
        f.asA {
            runner.inTransaction {
                val outer = f.runner.transactionToken()
                val value =
                    runner.outsideOpenTransaction {
                        f.asB { runner.inTransaction { f.runner.transactionToken() } }
                    }
                assertNotNull(value)
                assertNotSame(outer, value)
            }
        }
    }

    @Test
    fun `11 a runner without the override still refuses`() {
        val f = Fixture()
        val plain =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = f.runner.inTransaction(block)
            }
        f.asA {
            plain.inTransaction {
                val refused =
                    assertThrows(IllegalStateException::class.java) {
                        plain.outsideOpenTransaction {
                            f.asB { plain.inTransaction { f.runner.transactionToken() } }
                        }
                    }
                assertTrue(refused.message!!.startsWith(refusal))
            }
        }
        assertEquals(7, plain.outsideOpenTransaction { 7 })
    }

    @Test
    fun `12 a lane of 2 permits takes both and releases both`() {
        val f = Fixture(limit = 2)
        f.asA {
            f.runner.inTransaction {
                f.runner.outsideOpenTransaction { f.asB { f.runner.inTransaction { 1 } } }
                assertEquals(2, f.gate.acquired.get())
            }
        }
        assertEquals(2, f.gate.released.get())
    }

    @Test
    fun `13 a lane of 1 permit refuses the inner checkout and fails closed`() {
        val f = Fixture(limit = 1)
        f.asA {
            f.runner.inTransaction {
                val outer = f.runner.transactionToken()
                f.runner.outsideOpenTransaction {
                    val refused =
                        assertThrows(
                            SQLTransientConnectionException::class.java,
                        ) { f.asB { f.runner.inTransaction { f.runner.transactionToken() } } }
                    assertInstanceOf(LaneRefusedException::class.java, refused.cause)
                }
                assertSame(outer, f.runner.transactionToken())
                assertEquals(1, f.gate.acquired.get() - f.gate.released.get())
            }
        }
        assertEquals(1, f.gate.released.get())

        val zero = Fixture(limit = 0)
        val refused =
            assertThrows(
                SQLTransientConnectionException::class.java,
            ) { zero.asA { zero.runner.inTransaction { zero.runner.transactionToken() } } }
        assertInstanceOf(LaneRefusedException::class.java, refused.cause)
    }

    @Test
    @Tag("integration")
    fun `real pool of 2 connections runs both transactions`() {
        PostgresProbeFixture("postgres:18").use { fixture ->
            TestPoolFixture(
                fixture.jdbcUrl(),
                PostgresProbeFixture.APP,
                PostgresProbeFixture.APP_PASSWORD,
                maximumPoolSize = 2,
                connectionTimeoutMillis = 500,
            ).use { pool ->
                val tenants = TenantContextImpl()
                val runner = pool.runner(tenants, keys = SignedBinding.keys())
                val a = TenantId(UUID.randomUUID())
                val b = TenantId(UUID.randomUUID())
                tenants.bind(a, "actor") {
                    runner.inTransaction {
                        val outer = runner.transactionToken()
                        val inner =
                            runner.outsideOpenTransaction {
                                tenants.bind(b, "actor") { runner.inTransaction { runner.transactionToken() } }
                            }
                        assertNotNull(inner)
                        assertNotSame(outer, inner)
                        assertSame(outer, runner.transactionToken())
                    }
                }
            }
        }
    }
}
