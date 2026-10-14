package com.dynokore.platform.serviceruntime.eventloop

import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.RelayOutcome
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.DoubleSupplier

/** The loop itself (INV-T-3938): immediate repeat, interval, backoff, stop, an Error that ends it, the properties. */
class EventLoopTest {
    private fun loop(
        interval: Duration = Duration.ofSeconds(60),
        jitter: Double = 0.0,
        onEnded: (EventLoop) -> Unit = {},
        call: () -> Boolean,
    ) = EventLoop("test", EventLoopKind.RELAY, interval, Clock.systemUTC(), DoubleSupplier { jitter }, onEnded, call)

    private fun await(
        what: String,
        timeout: Duration = Duration.ofSeconds(10),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun `a call that did work is followed by the next at once, an idle one by the interval`() {
        val calls = AtomicInteger()
        val busy = loop { calls.incrementAndGet() < 50 }
        busy.start()
        await("50 calls with a 60 s interval") { calls.get() >= 50 }
        // the 50th call did no work: the loop now waits its interval
        Thread.sleep(300)
        calls.get() shouldBe 50
        busy.requestStop()
        busy.awaitStop(Duration.ofSeconds(5)) shouldBe true
        busy.isAlive() shouldBe false
    }

    @Test
    fun `an idle loop calls once per interval`() {
        val calls = AtomicInteger()
        val idle = loop(interval = Duration.ofMillis(200)) { calls.incrementAndGet() < 0 }
        idle.start()
        Thread.sleep(1100)
        idle.requestStop()
        idle.awaitStop(Duration.ofSeconds(5))
        calls.get() shouldBeGreaterThan 3
        calls.get() shouldBeLessThan 8
    }

    @Test
    fun `a call runs in the background lane`() {
        val seen = Collections.synchronizedList(mutableListOf<PoolLane>())
        val l =
            loop(interval = Duration.ofMillis(50)) {
                seen += PoolLanes.current()
                false
            }
        l.start()
        await("a call") { seen.isNotEmpty() }
        l.requestStop()
        l.awaitStop(Duration.ofSeconds(5))
        seen.toSet() shouldBe setOf(PoolLane.BACKGROUND)
    }

    @Test
    fun `a failing call is retried after a bounded jittered backoff and the loop goes on`() {
        val stamps = Collections.synchronizedList(mutableListOf<Long>())
        val calls = AtomicInteger()
        val l =
            loop(interval = Duration.ofMillis(40)) {
                stamps += System.nanoTime()
                if (calls.incrementAndGet() <= 3) throw SQLException("secret message", "08006") else calls.get() > 100
            }
        l.start()
        await("a successful call after 3 failures") { l.lastSuccess() != null }
        val gaps = stamps.zipWithNext { a, b -> TimeUnit.NANOSECONDS.toMillis(b - a) }
        // jitter 0: 40, 80, 160 ms
        gaps[0] shouldBeGreaterThanOrEqualTo 35
        gaps[1] shouldBeGreaterThanOrEqualTo 75
        gaps[2] shouldBeGreaterThanOrEqualTo 150
        l.ended() shouldBe null
        l.isAlive() shouldBe true
        l.requestStop()
        l.awaitStop(Duration.ofSeconds(5))
    }

    @Test
    fun `the backoff doubles per failure, is capped and jittered down to half`() {
        fun backoff(
            failures: Int,
            jitter: Double,
            interval: Duration = Duration.ofSeconds(1),
        ) = loop(interval = interval, jitter = jitter) { true }.backoff(failures).toMillis()
        backoff(1, 0.0) shouldBe 1_000
        backoff(2, 0.0) shouldBe 2_000
        backoff(5, 0.0) shouldBe 16_000
        backoff(6, 0.0) shouldBe 30_000
        backoff(10_000, 0.0) shouldBe 30_000
        backoff(6, 1.0) shouldBe 15_000
        // a 60 s interval is never shortened by the cap
        backoff(1, 0.0, Duration.ofSeconds(60)) shouldBe 60_000
        (1..50).forEach { n ->
            backoff(n, 0.37) shouldBeLessThanOrEqualTo 30_000
            backoff(n, 0.37) shouldBeGreaterThanOrEqualTo 1
        }
    }

    @Test
    fun `stop lets the call in hand finish and then ends the loop without interrupting it`() {
        val entered = CountDownLatch(1)
        val finished = AtomicInteger()
        val interrupted = AtomicInteger()
        val l =
            loop(interval = Duration.ofMillis(50)) {
                entered.countDown()
                try {
                    Thread.sleep(600)
                    finished.incrementAndGet()
                } catch (_: InterruptedException) {
                    interrupted.incrementAndGet()
                }
                true
            }
        l.start()
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        l.requestStop()
        l.awaitStop(Duration.ofSeconds(5)) shouldBe true
        finished.get() shouldBe 1
        interrupted.get() shouldBe 0
        l.ended() shouldBe null
    }

    @Test
    fun `a call that outlives the stop timeout is interrupted`() {
        val entered = CountDownLatch(1)
        val l =
            loop {
                entered.countDown()
                Thread.sleep(60_000)
                true
            }
        l.start()
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        l.requestStop()
        l.awaitStop(Duration.ofMillis(200)) shouldBe true
        l.isAlive() shouldBe false
    }

    @Test
    fun `an Error ends the loop, which says so and makes readiness DOWN`() {
        class Fatal : Error("fatal")

        val published = Collections.synchronizedList(mutableListOf<Any>())
        val relay =
            object : OutboxRelay {
                override fun runOnce(): RelayOutcome = throw Fatal()
            }
        val beans = StaticListableBeanFactory(mapOf<String, Any>("outboxRelay" to relay))
        val loops =
            EventLoops(beans, EventLoopProperties(), SimpleMeterRegistry(), { published += it })
        loops.start()
        val l = loops.loops().single()
        await("the loop to end") { !l.isAlive() }
        l.ended().shouldBeInstanceOf<Fatal>()
        published.filterIsInstance<AvailabilityChangeEvent<*>>().single().state shouldBe ReadinessState.REFUSING_TRAFFIC
        val health = EventLoopHealthIndicator { loops }.health()
        health.status shouldBe Status.DOWN
        health.details["ended"].toString() shouldBe "[relay:outboxRelay]"
        loops.stop()
    }

    @Test
    fun `one loop per relay, consumer and replay bean, each with a last-success gauge`() {
        val relays: Map<String, Any> =
            listOf("a", "b").associateWith {
                object : OutboxRelay {
                    override fun runOnce(): RelayOutcome = RelayOutcome(0, 0, false)
                }
            }
        val beans = StaticListableBeanFactory(relays)
        val registry = SimpleMeterRegistry()
        val loops =
            EventLoops(beans, EventLoopProperties(relayInterval = Duration.ofMillis(100)), registry, { _: Any -> })
        loops.start()
        loops.loops().map { it.name } shouldBe listOf("a", "b")
        await("both loops to call") { loops.loops().all { it.lastSuccess() != null } }
        (
            registry
                .get(EventLoops.LAST_SUCCESS)
                .tag("loop", "a")
                .tag("kind", "relay")
                .gauge()
                .value() > 0.0
        ) shouldBe
            true
        EventLoopHealthIndicator { loops }.health().status shouldBe Status.UP
        loops.stop()
        loops.isRunning shouldBe false
        loops.loops().none { it.isAlive() } shouldBe true
        // a second stop is a no-op; a start after a stop is a fresh start with fresh loops and gauges
        loops.stop()
        registry.find(EventLoops.LAST_SUCCESS).gauges().size shouldBe 0
        loops.start()
        loops.loops().size shouldBe 2
        await("both fresh loops to call") { loops.loops().all { it.lastSuccess() != null } }
        (
            registry
                .get(EventLoops.LAST_SUCCESS)
                .tag("loop", "a")
                .gauge()
                .value() > 0.0
        ) shouldBe true
        loops.stop()
    }

    @ParameterizedTest
    @ValueSource(strings = ["relay-interval=99ms", "consumer-interval=61s", "replay-interval=0s", "relay-interval=-1s"])
    fun `an interval outside 100 ms to 60 s stops start-up`(property: String) {
        ApplicationContextRunner()
            .withUserConfiguration(EventLoopWiring::class.java)
            .withPropertyValues("service-runtime.event-loops.$property")
            .run { it.startupFailure.shouldBeInstanceOf<Exception>() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "true"])
    fun `the loops are on by default, enabled=false switches them all off, and the boundary values are accepted`(
        enabled: String,
    ) {
        ApplicationContextRunner()
            .withUserConfiguration(EventLoopWiring::class.java)
            .withPropertyValues(
                "service-runtime.event-loops.enabled=$enabled",
                "service-runtime.event-loops.relay-interval=100ms",
                "service-runtime.event-loops.consumer-interval=60s",
            ).run {
                it.startupFailure shouldBe null
                it.getBeansOfType(EventLoops::class.java).size shouldBe if (enabled == "true") 1 else 0
                it.getBeansOfType(EventLoopHealthIndicator::class.java).size shouldBe 1
            }
        ApplicationContextRunner().withUserConfiguration(EventLoopWiring::class.java).run {
            it.getBean(EventLoopProperties::class.java).enabled shouldBe true
            it.getBean(EventLoopProperties::class.java).relayInterval shouldBe Duration.ofSeconds(1)
            it.getBeansOfType(EventLoops::class.java).size shouldBe 1
        }
    }

    @Test
    fun `an invalid interval is refused by the constructor too`() {
        shouldThrow<IllegalArgumentException> { EventLoopProperties(relayInterval = Duration.ofMillis(99)) }
        EventLoopProperties(relayInterval = Duration.ofMillis(100), consumerInterval = Duration.ofSeconds(60))
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
