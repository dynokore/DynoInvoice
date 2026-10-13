package com.dynokore.platform.contracttests.moduleapi

import com.dynokore.platform.adapterruntime.AdapterState
import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.ConnectionState
import com.dynokore.platform.adapterruntime.ConnectionStatus
import com.dynokore.platform.adapterruntime.ConnectionStatusReporter
import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink
import com.dynokore.platform.adapterruntime.ManagedAdapter
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.ResilientExecutor
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeBasedCutoverRule
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.connection.InMemoryConnectionStatusReporter
import com.dynokore.platform.adapterruntime.cutover.VersionedAdapterRouter
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * adapter-runtime's module-API consumer contract (INV-T-2877 rev 5, ruling INV-Q-3925): a fake consumer written the
 * way an external-edge adapter of `transport-svc` is written — constructor-injected against only the
 * `adapter-runtime-api` ports [ResilientExecutor], [ConnectionStatusReporter] and [ManagedAdapter] versions under a
 * [com.dynokore.platform.adapterruntime.CutoverRule] — run against an implementation set. [sink] supplies the
 * [DeadLetterSink]: [AdapterRuntimeModuleApiConsumerContractTest] runs the contract against the real
 * implementations, and [BrokenDeadLetterSinkFixture] against a deliberately broken one, which must fail it
 * (done-when: a broken module-API fixture fails its consumer test).
 */
abstract class AdapterRuntimeModuleApiConsumerContract {
    /** The dead-letter sink under test; the real one unless a broken fixture overrides it. */
    protected abstract fun sink(): DeadLetterSink

    /** What the sink received, as the consumer's operator would read it back. */
    protected abstract fun deadLetters(): List<DeadLetterEntry>

    /** Written as a `transport-svc` Access Point sender: only `-api` types in its constructor. */
    private class FakeConsumerSender(
        private val executor: ResilientExecutor,
        private val status: ConnectionStatusReporter,
        private val version: AdapterVersion,
        private val clock: Clock,
    ) {
        fun send(transmit: () -> String): String =
            try {
                executor.execute(transmit).also { report(ConnectionState.UP, null) }
            } catch (e: IOException) {
                report(ConnectionState.DOWN, e.message)
                throw e
            }

        private fun report(
            state: ConnectionState,
            detail: String?,
        ) = status.report(ConnectionStatus(version, state, clock.instant(), detail))
    }

    private class Adapter(
        override val version: AdapterVersion,
    ) : ManagedAdapter {
        override var state = AdapterState.STARTING

        override fun start() {
            state = AdapterState.RUNNING
        }

        override fun drainAndStop(deadline: Instant) {
            state = AdapterState.STOPPED
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC)
    private val version = AdapterVersion("peppol-ap-2.1")
    private val policy =
        ResiliencePolicy(
            timeout = TimeoutPolicy(Duration.ofSeconds(2)),
            retry = RetryPolicy(maxAttempts = 3, Duration.ofMillis(1), Duration.ofMillis(2), jitter = 0.0),
            circuitBreaker = CircuitBreakerPolicy(100.0F, 50, Duration.ofSeconds(30), 1),
            bulkhead = BulkheadPolicy(4, Duration.ZERO),
        )

    private fun <T> withSender(block: (FakeConsumerSender, ConnectionStatusReporter) -> T): T {
        val reporter = InMemoryConnectionStatusReporter()
        return Resilience4jResilientExecutor(policy, EDGE, sink(), clock).use { executor ->
            block(FakeConsumerSender(executor, reporter, version, clock), reporter)
        }
    }

    @Test
    fun `a transient failure is retried within the policy and the connection is reported up`() {
        var calls = 0
        withSender { sender, status ->
            sender.send {
                calls += 1
                if (calls < 3) throw IOException("connection reset") else "MDN-1"
            } shouldBe "MDN-1"
            status.current(version)?.state shouldBe ConnectionState.UP
        }
        calls shouldBe 3
        deadLetters() shouldBe emptyList()
    }

    @Test
    fun `a call that fails every attempt is dead-lettered once with its edge and attempts, then rethrown`() {
        withSender { sender, status ->
            assertThrows(IOException::class.java) { sender.send { throw IOException("AP unreachable") } }
            status.current(version)?.state shouldBe ConnectionState.DOWN
        }
        deadLetters() shouldBe listOf(DeadLetterEntry(EDGE, 3, clock.instant(), IOException::class.qualifiedName!!))
    }

    @Test
    fun `new work goes to the version the cutover rule names and the inactive version is drained`() {
        val before = Adapter(AdapterVersion("peppol-ap-2.0"))
        val after = Adapter(version)
        val rule = TimeBasedCutoverRule(before.version, after.version, clock.instant())
        val router = VersionedAdapterRouter(listOf(before, after), rule, clock)
        router.current() shouldBe after
        router.retireInactive(clock.instant())
        before.state shouldBe AdapterState.STOPPED
        after.state shouldBe AdapterState.RUNNING
    }

    private companion object {
        const val EDGE = "transport-svc->peppol-ap"
    }
}

/** The contract against adapter-runtime's real implementations. */
@Tag("integration")
class AdapterRuntimeModuleApiConsumerContractTest : AdapterRuntimeModuleApiConsumerContract() {
    private val sink = InMemoryDeadLetterSink()

    override fun sink(): DeadLetterSink = sink

    override fun deadLetters(): List<DeadLetterEntry> = sink.entries
}
