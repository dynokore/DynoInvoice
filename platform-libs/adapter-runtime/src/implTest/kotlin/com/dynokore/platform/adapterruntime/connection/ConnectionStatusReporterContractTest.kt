package com.dynokore.platform.adapterruntime.connection

import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.ConnectionState
import com.dynokore.platform.adapterruntime.ConnectionStatus
import com.dynokore.platform.adapterruntime.ConnectionStatusReporter
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** The port contract every [ConnectionStatusReporter] implementation must satisfy. */
abstract class ConnectionStatusReporterContractTest {
    abstract fun reporter(): ConnectionStatusReporter

    private val version = AdapterVersion("1.0.0")

    @Test
    fun `current is null before any report`() {
        reporter().current(version).shouldBeNull()
    }

    @Test
    fun `current returns the latest report for that version`() {
        val reporter = reporter()
        val first = ConnectionStatus(version, ConnectionState.DOWN, Instant.parse("2026-09-27T00:00:00Z"))
        val second = ConnectionStatus(version, ConnectionState.UP, Instant.parse("2026-09-27T00:05:00Z"))

        reporter.report(first)
        reporter.report(second)

        reporter.current(version) shouldBe second
    }

    @Test
    fun `reports for one version never affect another version's status`() {
        val reporter = reporter()
        val other = AdapterVersion("2.0.0")

        reporter.report(ConnectionStatus(version, ConnectionState.UP, Instant.parse("2026-09-27T00:00:00Z")))

        reporter.current(other).shouldBeNull()
    }
}
