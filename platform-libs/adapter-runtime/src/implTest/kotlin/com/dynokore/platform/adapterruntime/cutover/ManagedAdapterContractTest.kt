package com.dynokore.platform.adapterruntime.cutover

import com.dynokore.platform.adapterruntime.AdapterState
import com.dynokore.platform.adapterruntime.ManagedAdapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The port contract every [ManagedAdapter] implementation must satisfy. */
abstract class ManagedAdapterContractTest {
    /** A fresh, not-yet-started adapter. */
    abstract fun adapter(): ManagedAdapter

    @Test
    fun `starts in a non-RUNNING state`() {
        adapter().state shouldBe AdapterState.STARTING
    }

    @Test
    fun `is RUNNING once started`() {
        val a = adapter()
        a.start()
        a.state shouldBe AdapterState.RUNNING
    }

    @Test
    fun `is STOPPED after draining with no in-flight work`() {
        val a = adapter()
        a.start()
        a.drainAndStop(Instant.now().plus(5, ChronoUnit.SECONDS))
        a.state shouldBe AdapterState.STOPPED
    }

    @Test
    fun `drainAndStop is idempotent`() {
        val a = adapter()
        a.start()
        val deadline = Instant.now().plus(5, ChronoUnit.SECONDS)
        a.drainAndStop(deadline)
        a.drainAndStop(deadline)
        a.state shouldBe AdapterState.STOPPED
    }
}
