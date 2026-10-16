package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

/**
 * The lane permit of [TenantAwareDataSource] is released exactly once on a failed pool checkout, a double close and a
 * failed close (INV-Q-4502 (A)). A `Connection` stub names a JDBC type and issues no SQL statement of its own
 * (INV-Q-5327).
 */
class TenantAwareDataSourceConnectionTest {
    private class CountingGate : LaneGate {
        var acquired = 0
        var released = 0

        override fun acquire(): LanePermit {
            acquired++
            return LanePermit { released++ }
        }
    }

    private class Physical(
        private val failOnClose: Boolean = false,
    ) {
        var closes = 0
        var resets = 0
        private var closed = false

        val connection: Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
                when (method.name) {
                    "isClosed" -> {
                        closed
                    }

                    "createStatement" -> {
                        statement()
                    }

                    "close" -> {
                        closes++
                        closed = true
                        if (failOnClose) throw SQLException("close failed")
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as Connection

        private fun statement(): Statement =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, method, _ ->
                when (method.name) {
                    "execute" -> {
                        resets++
                        false
                    }

                    "close" -> {
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as Statement
    }

    private fun pool(take: () -> Connection): DataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name != "getConnection") throw UnsupportedOperationException(method.name)
            try {
                take()
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as DataSource

    @Test
    fun `a closed connection releases the permit once and resets the session`() {
        val gate = CountingGate()
        val physical = Physical()
        val connection = TenantAwareDataSource(pool { physical.connection }, gate).connection
        assertEquals(1, gate.acquired)
        assertEquals(0, gate.released)

        connection.close()

        assertEquals(1, gate.released)
        assertEquals(2, physical.resets)
        assertEquals(1, physical.closes)
    }

    @Test
    fun `a failed pool checkout releases the permit once and rethrows the pool failure`() {
        val gate = CountingGate()
        val failure = SQLException("pool exhausted")

        val thrown =
            assertThrows(SQLException::class.java) { TenantAwareDataSource(pool { throw failure }, gate).connection }

        assertSame(failure, thrown)
        assertEquals(1, gate.acquired)
        assertEquals(1, gate.released)
    }

    @Test
    fun `closing twice releases the permit once and closes the physical connection once`() {
        val gate = CountingGate()
        val physical = Physical()
        val connection = TenantAwareDataSource(pool { physical.connection }, gate).connection

        connection.close()
        connection.close()

        assertEquals(1, gate.released)
        assertEquals(1, physical.closes)
        assertEquals(2, physical.resets)
    }

    @Test
    fun `a failed close still releases the permit once and a second close does nothing`() {
        val gate = CountingGate()
        val physical = Physical(failOnClose = true)
        val connection = TenantAwareDataSource(pool { physical.connection }, gate).connection

        assertThrows(SQLException::class.java) { connection.close() }
        connection.close()

        assertEquals(1, gate.released)
        assertEquals(1, physical.closes)
    }
}
