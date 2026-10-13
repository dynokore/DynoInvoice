package com.dynokore.platform.contracttests.moduleapi

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.RetryProperties
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ConnectException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * service-runtime's module-API consumer contract test (INV-T-2877 rev 5, ruling INV-Q-3925): a fake consumer written
 * the way a service's use case calls another service over an internal edge (STK-24, INV-REQ-1203) — constructor-
 * injected against only the `service-runtime-api` [ResilienceRegistry] and [Deadline] — run against the real
 * [Resilience4jResilienceRegistry] built from [ResilienceProperties] as service-runtime's auto-configuration binds
 * them. If the per-edge instance, the deadline check, the retry, the timeout or the connect-failure outcome ever
 * drifts from what a service is written to expect, this fails.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceRuntimeModuleApiConsumerContractTest {
    /** The outcome a `submission-svc` use case maps each edge result to. */
    private enum class Outcome { DONE, NOT_PERMITTED, UNREACHABLE, TIMED_OUT }

    /** Written as a `submission-svc` use case calling `rules-svc`: only `-api` types in its constructor. */
    private class FakeConsumerUseCase(
        private val edges: ResilienceRegistry,
        private val clock: Clock,
    ) {
        fun evaluate(
            budget: Duration,
            call: () -> Outcome,
        ): Outcome =
            edges.forEdge(EDGE).execute(
                deadline = Deadline.after(budget, clock),
                onCallNotPermitted = { Outcome.NOT_PERMITTED },
                onConnectFailure = { Outcome.UNREACHABLE },
                onTimeout = { Outcome.TIMED_OUT },
                block = call,
            )
    }

    private val clock = Clock.systemUTC()
    private val registry =
        Resilience4jResilienceRegistry(
            ResilienceProperties(
                edges =
                    mapOf(
                        EDGE to
                            EdgeProperties(
                                timeout = Duration.ofMillis(300),
                                retry = RetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(2)),
                            ),
                    ),
            ),
            clock,
        )
    private val useCase = FakeConsumerUseCase(registry, clock)

    @AfterAll
    fun stop() = registry.close()

    @Test
    fun `one edge name is one edge, so its breaker and bulkhead state is kept across calls`() {
        registry.forEdge(EDGE) shouldBeSameInstanceAs registry.forEdge(EDGE)
    }

    @Test
    fun `a call within its budget returns the block's result, after retrying a transient failure`() {
        val calls = AtomicInteger()
        useCase.evaluate(Duration.ofSeconds(5)) {
            if (calls.incrementAndGet() < 2) error("transient") else Outcome.DONE
        } shouldBe Outcome.DONE
        calls.get() shouldBe 2
    }

    @Test
    fun `a budget already spent is a timeout, and the call is never attempted`() {
        val calls = AtomicInteger()
        useCase.evaluate(Duration.ZERO) {
            calls.incrementAndGet()
            Outcome.DONE
        } shouldBe Outcome.TIMED_OUT
        calls.get() shouldBe 0
    }

    @Test
    fun `a peer that refuses the connection is reported unreachable`() {
        useCase.evaluate(Duration.ofSeconds(5)) { throw ConnectException("connection refused") } shouldBe
            Outcome.UNREACHABLE
    }

    @Test
    fun `a peer slower than the edge timeout is reported as a timeout`() {
        useCase.evaluate(Duration.ofSeconds(5)) {
            Thread.sleep(2_000)
            Outcome.DONE
        } shouldBe Outcome.TIMED_OUT
    }

    private companion object {
        const val EDGE = "submission-svc->rules-svc"
    }
}
