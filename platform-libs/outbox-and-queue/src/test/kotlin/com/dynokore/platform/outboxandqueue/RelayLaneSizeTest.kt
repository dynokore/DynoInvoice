package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.LanePermit
import com.dynokore.platform.tenancykernel.LaneRefusedException
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TestPoolFixture
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The relay holds an outer transaction and a row transaction, so its lane needs at least 2 permits (owner answer
 * INV-Q-5609 (b)): 2 and 3 run, 1 and 0 are refused at the
 * first run with the lane and its size, before any connection is taken.
 */
class RelayLaneSizeTest {
    private val taken = AtomicInteger()

    /** A pool that is never reached: a refused run takes no lane permit, so no connection is asked for. */
    private val pool = TestPoolFixture("jdbc:postgresql://127.0.0.1:1/none", "nobody", "none")

    private fun relay(laneSize: Int): JdbcOutboxRelay {
        val tenants = TenantContextImpl()
        val runner =
            pool.runner(
                tenants,
                object : LaneGate {
                    override fun acquire(): LanePermit {
                        taken.incrementAndGet()
                        return LanePermit {}
                    }
                },
                keys = SigningFixture.keys(),
            )
        return JdbcOutboxRelay(
            TenantScope(tenants, runner, runner, "svc:test"),
            DSL.using(SQLDialect.POSTGRES),
            "payload",
            object : EventBinding {
                override fun publish(
                    topic: String,
                    envelope: Envelope,
                ) = Unit
            },
            Clock.systemUTC(),
            "background",
            laneSize,
        )
    }

    @ParameterizedTest(name = "lane size {0} starts")
    @ValueSource(ints = [2, 3])
    fun `a lane of at least 2 permits starts the relay`(size: Int) {
        relay(size)
        assertEquals(0, taken.get())
    }

    @ParameterizedTest(name = "lane size {0} is refused")
    @ValueSource(ints = [1, 0, -1])
    fun `a lane of fewer than 2 permits is refused, naming the lane and its size, before any connection`(size: Int) {
        val refused = assertThrows(LaneRefusedException::class.java) { relay(size).runOnce() }
        assertTrue(refused.message!!.contains("BACKGROUND") && refused.message!!.contains("has $size"), refused.message)
        assertEquals(0, taken.get())
    }
}
