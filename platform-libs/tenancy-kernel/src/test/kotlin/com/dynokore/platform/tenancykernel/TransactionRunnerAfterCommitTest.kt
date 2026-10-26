package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * INV-T-11066 (INV-Q-11056): `TransactionRunner.afterCommit` against a `Connection` stub with no database. Each test
 * compares the recorded event list (`commit`, `rollback`, `close` of the connection and the actions) by equality.
 */
class TransactionRunnerAfterCommitTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val key = SignedBinding.key()
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class Recorder(
        private val failCommit: Boolean = false,
    ) : DataSource {
        override fun getConnection(): Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
                when (m.name) {
                    "prepareStatement" -> statement()
                    "commit" -> if (failCommit) throw IllegalStateException("commit failed") else events.add("commit")
                    "rollback", "close" -> events.add(m.name)
                    "setAutoCommit", "isClosed" -> if (m.name == "isClosed") false else null
                    else -> throw UnsupportedOperationException(m.name)
                }
            } as Connection

        private fun statement(): PreparedStatement =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, m, _ ->
                when (m.name) {
                    "setString", "close" -> null
                    "execute" -> false
                    else -> throw UnsupportedOperationException(m.name)
                }
            } as PreparedStatement

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connection

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false

        override fun getLogWriter() = null

        override fun setLogWriter(out: java.io.PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): java.util.logging.Logger = throw UnsupportedOperationException()
    }

    private val tenants = TenantContextImpl()

    private fun runner(source: DataSource = Recorder()) =
        TransactionRunnerImpl(source, tenants, null, SignedBinding.keys(key))

    private fun <T> bound(block: () -> T): T = tenants.bind(tenant, "user-a", block)

    @Test
    fun `a runner that implements only inTransaction refuses afterCommit with its class name and runs nothing`() {
        class OnlyInTransaction : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
        val failure =
            assertThrows(UnsupportedOperationException::class.java) {
                OnlyInTransaction().afterCommit {
                    events +=
                        "action"
                }
            }
        assertTrue(failure.message!!.contains(OnlyInTransaction::class.java.name), failure.message)
        assertEquals(emptyList<String>(), events.toList())
    }

    @Test
    fun `a registers on the outermost transaction and a nested join registers on the same one`() {
        val runner = runner()
        bound {
            runner.inTransaction {
                runner.afterCommit { events += "outer" }
                runner.inTransaction { runner.afterCommit { events += "inner" } }
                events += "block end"
            }
        }
        assertEquals(listOf("block end", "commit", "close", "outer", "inner"), events.toList())
    }

    @Test
    fun `b each action runs once in registration order after commit and close on the calling thread`() {
        val runner = runner()
        val caller = Thread.currentThread()
        bound {
            runner.inTransaction {
                runner.afterCommit { events += "action 1 same thread=${Thread.currentThread() === caller}" }
                runner.afterCommit { events += "action 2" }
            }
        }
        assertEquals(listOf("commit", "close", "action 1 same thread=true", "action 2"), events.toList())
    }

    @Test
    fun `c no action runs when the block throws or when commit throws`() {
        val runner = runner()
        assertThrows(IllegalArgumentException::class.java) {
            bound {
                runner.inTransaction {
                    runner.afterCommit { events += "action" }
                    throw IllegalArgumentException("block")
                }
            }
        }
        assertEquals(listOf("rollback", "close"), events.toList())
        events.clear()
        val failing = runner(Recorder(failCommit = true))
        assertThrows(IllegalStateException::class.java) {
            bound { failing.inTransaction { failing.afterCommit { events += "action" } } }
        }
        assertEquals(listOf("close"), events.toList())
    }

    @Test
    fun `d afterCommit with no open transaction, also inside outsideOpenTransaction, throws and registers nothing`() {
        val runner = runner()
        assertThrows(IllegalStateException::class.java) { runner.afterCommit { events += "bare" } }
        bound {
            runner.inTransaction {
                runner.outsideOpenTransaction {
                    assertThrows(IllegalStateException::class.java) { runner.afterCommit { events += "outside" } }
                }
            }
        }
        assertEquals(listOf("commit", "close"), events.toList())
    }

    @Test
    fun `e an action that throws does not stop later actions nor change the result, an Error is not caught`() {
        val runner = runner()
        val result =
            bound {
                runner.inTransaction {
                    runner.afterCommit { throw IllegalStateException("boom") }
                    runner.afterCommit { events += "after boom" }
                    "value"
                }
            }
        assertEquals("value", result)
        assertEquals(listOf("commit", "close", "after boom"), events.toList())
        events.clear()
        assertThrows(StackOverflowError::class.java) {
            bound {
                runner.inTransaction {
                    runner.afterCommit { throw StackOverflowError("error") }
                    runner.afterCommit { events += "never" }
                }
            }
        }
        assertEquals(listOf("commit", "close"), events.toList())
    }

    @Test
    fun `f an action may open a new transaction and an afterCommit made in an action with none open is refused`() {
        val runner = runner()
        bound {
            runner.inTransaction {
                runner.afterCommit {
                    runner.inTransaction { runner.afterCommit { events += "second" } }
                    events += "first done"
                }
                runner.afterCommit {
                    try {
                        runner.afterCommit { events += "never" }
                    } catch (refused: IllegalStateException) {
                        assertTrue(refused.message!!.contains("afterCommit"), refused.message)
                        events += "refused"
                    }
                }
            }
        }
        assertEquals(listOf("commit", "close", "commit", "close", "second", "first done", "refused"), events.toList())
    }

    @Test
    fun `g two threads that each run a transaction never run each other's actions`() {
        val runner = runner()
        val bothOpen = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads =
            listOf("A", "B").map { name ->
                Thread {
                    try {
                        bound {
                            runner.inTransaction {
                                runner.afterCommit { events += "action $name on ${Thread.currentThread().name}" }
                                bothOpen.countDown()
                                check(bothOpen.await(10, TimeUnit.SECONDS)) { "the other thread never opened" }
                            }
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }.also { it.name = "thread-$name" }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(
            listOf("action A on thread-A", "action B on thread-B"),
            events.filter { it.startsWith("action") }.sorted(),
        )
        assertEquals(2, events.count { it == "commit" })
    }
}
