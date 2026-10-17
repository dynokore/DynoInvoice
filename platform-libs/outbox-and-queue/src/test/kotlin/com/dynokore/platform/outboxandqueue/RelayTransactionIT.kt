package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.LanePermit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger

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
            assertEquals("3F000", JooqKit.sqlState(refused), refused?.toString())
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
            val gate = PeakGate()
            JooqKit.Access.runtime(harness.fixture, emptyMap(), gate).use { access ->
                val relay =
                    JdbcOutboxRelay(
                        TenantScope(access.tenants, access.runner, access.runner, OutboxPostgresFixture.WORKLOAD),
                        JooqKit.module("payload", access.tenants, access.runner),
                        "payload",
                        harness.binding(),
                        ServiceKit.CLOCK,
                        "background",
                        2,
                    )
                assertEquals(ROWS.toLong(), relay.runOnce().published.toLong())
            }
            assertTrue(gate.peak() in 1..2, "peak connections of the relay thread: ${gate.peak()}")
        }
    }

    /** A lane gate that admits every connection and counts those held at once, and their peak. */
    private class PeakGate : LaneGate {
        private val held = AtomicInteger()
        private val highest = AtomicInteger()

        fun peak(): Int = highest.get()

        override fun acquire(): LanePermit {
            highest.accumulateAndGet(held.incrementAndGet(), ::maxOf)
            return LanePermit { held.decrementAndGet() }
        }
    }

    private companion object {
        const val ROWS = 3
    }
}
