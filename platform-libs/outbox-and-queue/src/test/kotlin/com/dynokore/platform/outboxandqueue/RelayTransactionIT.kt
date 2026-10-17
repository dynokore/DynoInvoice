package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The relay's outer transaction (owner answer INV-Q-5609 (b)): it commits after the last row, rolls back when the
 * claim fails, and the relay thread never holds more than 2 connections.
 */
@Tag("integration")
class RelayTransactionIT {
    private val claim = RelayClaimIT()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the outer transaction commits after the last row`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val observer = ServiceKit(harness, "payload", "payload-svc", harness.catalogue)
            claim.plant(kit, ROWS)
            val outcome = kit.relay.runOnce()
            assertEquals(ROWS.toLong(), outcome.published.toLong())
            assertEquals(0L, outcome.pending)
            assertEquals(0L, claim.advisoryHolders(observer), "the lock ends with the outer transaction")
            assertEquals(
                ROWS.toLong(),
                harness.count("select count(*) from payload.outbox where published_at is not null"),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the outer transaction rolls back when the claim fails`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val observer = ServiceKit(harness, "payload", "payload-svc", harness.catalogue)
            claim.plant(kit, ROWS)
            // a schema without the claim functions: the lock is taken, then the claim read fails
            val ghost = JooqKit.module("ghost", kit.tenants, kit.boundary)
            val failing =
                JdbcOutboxRelay(kit.scope, ghost, "ghost", harness.binding(), ServiceKit.CLOCK, "background", 2)
            val refused = runCatching { failing.runOnce() }.exceptionOrNull()
            assertEquals("42883", JooqKit.sqlState(refused), refused?.toString())
            assertEquals(0L, claim.advisoryHolders(observer), "the lock ended with the rolled back outer transaction")
            assertEquals(0L, harness.count("select count(*) from payload.outbox where published_at is not null"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the relay thread holds at most 2 connections`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            claim.plant(kit, ROWS)
            val observed = PeakConnections(harness.fixture.appDataSource())
            val tenants = TenantContextImpl()
            val runner = TransactionRunnerImpl(TenantAwareDataSource(observed), tenants)
            val relay =
                JdbcOutboxRelay(
                    TenantScope(tenants, runner, runner, OutboxPostgresFixture.WORKLOAD),
                    JooqKit.module("payload", tenants, runner),
                    "payload",
                    harness.binding(),
                    ServiceKit.CLOCK,
                    "background",
                    2,
                )
            assertEquals(ROWS.toLong(), relay.runOnce().published.toLong())
            assertTrue(observed.peak() in 1..2, "peak connections of the relay thread: ${observed.peak()}")
        }
    }

    /** Counts the connections taken from [delegate] and not yet closed, and their peak. */
    private class PeakConnections(
        delegate: DataSource,
    ) : DataSource by (
            Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, method, args ->
                val result = method.invoke(delegate, *(args ?: emptyArray()))
                if (method.name == "getConnection") counted(result as Connection) else result
            } as DataSource
        ) {
        fun peak(): Int = highest.get()

        private companion object {
            val open = AtomicInteger()
            val highest = AtomicInteger()

            fun counted(connection: Connection): Connection {
                highest.accumulateAndGet(open.incrementAndGet(), ::maxOf)
                return Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, args ->
                    if (method.name == "close") open.decrementAndGet()
                    method.invoke(connection, *(args ?: emptyArray()))
                } as Connection
            }
        }
    }

    private companion object {
        const val ROWS = 3
    }
}
