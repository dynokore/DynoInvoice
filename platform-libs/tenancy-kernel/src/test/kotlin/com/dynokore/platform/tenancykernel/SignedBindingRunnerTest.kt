package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-5359 (INV-REQ-854 b7, INV-Q-4121, INV-Q-5354 (A)): the runner's first statement with and without a key source,
 * against a `Connection` stub (no database): the signature is a bind parameter, its expiry never passes the start plus
 * the timeout the runner was built with, and a source with no key is refused before any statement.
 */
class SignedBindingRunnerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val key = SignedBinding.key()

    private class Recorder : DataSource {
        val sql = mutableListOf<String>()
        val params = mutableListOf<String>()
        var connections = 0

        override fun getConnection(): Connection {
            connections++
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                when (m.name) {
                    "prepareStatement" -> {
                        sql += args!![0] as String
                        statement()
                    }

                    "isClosed" -> {
                        false
                    }

                    "setAutoCommit", "commit", "rollback", "close" -> {
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(m.name)
                    }
                }
            } as Connection
        }

        private fun statement(): PreparedStatement =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, m, args ->
                when (m.name) {
                    "setString" -> {
                        params += args!![1] as String
                        null
                    }

                    "execute" -> {
                        false
                    }

                    "close" -> {
                        null
                    }

                    else -> {
                        throw UnsupportedOperationException(m.name)
                    }
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

    private fun run(
        runner: TransactionRunnerImpl,
        tenants: TenantContextImpl,
    ) = tenants.bind(tenant, "user-a") { runner.inTransaction { } }

    @Test
    fun `no key source sets no signature, whatever the label`() {
        for (label in listOf<ConnectionLabel?>(null, ConnectionLabel { "svc:lane" })) {
            val ds = Recorder()
            val tenants = TenantContextImpl()
            run(TransactionRunnerImpl(ds, tenants, label), tenants)
            assertFalse(ds.sql.single().contains("binding_sig"), ds.sql.toString())
            assertEquals(if (label == null) 2 else 3, ds.params.size)
        }
    }

    @Test
    fun `a key source puts the signature in the first statement as a bind parameter only`() {
        for (label in listOf<ConnectionLabel?>(null, ConnectionLabel { "svc:lane" })) {
            val ds = Recorder()
            val tenants = TenantContextImpl()
            val start = Instant.parse("2026-10-18T10:00:00Z")
            val runner =
                TransactionRunnerImpl(ds, tenants, label, SignedBinding.keys(key), Duration.ofSeconds(30), fixed(start))
            run(runner, tenants)
            val sql = ds.sql.single()
            assertTrue(sql.contains("set_config('app.binding_sig', ?, true)"), sql)
            val sig = ds.params[2]
            assertEquals(SignedBinding.value(key, tenant, "user-a", start), sig)
            assertFalse(sql.contains(sig) || sql.contains(tenant.toString()), "the statement text holds no value")
            assertEquals(if (label == null) 3 else 4, ds.params.size)
            if (label != null) assertEquals("svc:lane", ds.params[3])
        }
    }

    @Test
    fun `the expiry is no later than start plus the timeout, equality allowed, one millisecond more refused`() {
        for (startText in listOf("2026-10-18T10:00:00Z", "2026-10-18T10:00:00.999Z", "2026-10-18T10:00:00.001Z")) {
            val start = Instant.parse(startText)
            for (timeout in listOf(Duration.ofSeconds(30), Duration.ofMillis(45_500), Duration.ofMinutes(5))) {
                val ds = Recorder()
                val tenants = TenantContextImpl()
                run(
                    TransactionRunnerImpl(ds, tenants, null, SignedBinding.keys(key), timeout, fixed(start)),
                    tenants,
                )
                val expiry = ds.params[2].split('.')[2].toLong()
                assertTrue(BindingSignature.withinTimeout(expiry, start, timeout), "$startText + $timeout: $expiry")
                assertTrue(expiry * 1000 > start.toEpochMilli() + timeout.toMillis() - 1000, "no earlier than needed")
            }
        }
        val start = Instant.parse("2026-10-18T10:00:00Z")
        val timeout = Duration.ofSeconds(30)
        val limit = (start.toEpochMilli() + timeout.toMillis()) / 1000
        assertTrue(BindingSignature.withinTimeout(limit, start, timeout), "equality")
        assertFalse(BindingSignature.withinTimeout(limit + 1, start, timeout), "one second more")
        assertFalse(
            BindingSignature.withinTimeout(limit, start.minusMillis(1), timeout),
            "a start 1 ms earlier makes the same expiry 1 ms late",
        )
    }

    @Test
    fun `a source with no current key is refused before any connection`() {
        for (source in listOf(SignedBinding.keys(current = null), SignedBinding.keys(current = ByteArray(0)))) {
            val ds = Recorder()
            val tenants = TenantContextImpl()
            val runner = TransactionRunnerImpl(ds, tenants, null, source)
            assertThrows(IllegalStateException::class.java) { run(runner, tenants) }
            assertEquals(0, ds.connections)
        }
    }

    @Test
    fun `a zero or negative timeout is refused at construction`() {
        for (timeout in listOf(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThrows(IllegalArgumentException::class.java) {
                TransactionRunnerImpl(Recorder(), TenantContextImpl(), null, SignedBinding.keys(key), timeout)
            }
        }
    }

    private fun fixed(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)
}
