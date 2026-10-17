package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.LaneRefusedException
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The relay holds an outer transaction and a row transaction, so its lane needs at least 2 permits (owner answer
 * INV-Q-5609 (b)): 2 and 3 run, 1 and 0 are refused at the first run with the lane and its size, before any connection is taken.
 */
class RelayLaneSizeTest {
    private val taken = AtomicInteger()

    private val source: DataSource =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.returnType == Connection::class.java) taken.incrementAndGet()
            throw UnsupportedOperationException(method.name)
        } as DataSource

    private fun relay(laneSize: Int): JdbcOutboxRelay {
        val tenants = TenantContextImpl()
        val runner = TransactionRunnerImpl(source, tenants)
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
