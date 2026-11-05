package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.RetryBudget
import com.dynokore.platform.serviceruntime.resilience.RetryPolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.net.ConnectException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** INV-T-9099, INV-AC-9091: the retry budget of an edge, with a clock the test moves. */
class RetryBudgetEdgeTest {
    private class MovableClock(
        var now: Instant = Instant.parse("2026-11-05T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock()
    private val meters = SimpleMeterRegistry()
    private val deadline = Deadline.after(Duration.ofSeconds(30), Clock.fixed(clock.now, ZoneId.of("UTC")))

    private fun edge(
        name: String,
        retry: RetryPolicy?,
    ) = Resilience4jResilientEdge(
        name,
        EdgePolicy(
            TimeoutPolicy(Duration.ofMillis(500)),
            retry,
            CircuitBreakerPolicy(50F, 1000, Duration.ofSeconds(10), 1),
            BulkheadPolicy(16, Duration.ZERO),
        ),
        clock,
        meters = meters,
    )

    private fun retry(budget: RetryBudget?) = RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2), 0.0, budget)

    private fun Resilience4jResilientEdge.call(block: () -> String): String =
        execute(deadline, { "refused" }, { "unavailable" }, { "timed-out" }, block)

    /** A stub whose first attempt of every call fails and whose second succeeds; returns the attempts made. */
    private fun Resilience4jResilientEdge.callFailingFirstAttempt(attempts: AtomicInteger): String {
        val own = AtomicInteger()
        return call {
            attempts.incrementAndGet()
            if (own.incrementAndGet() == 1) throw ConnectException("down") else "ok"
        }
    }

    private fun exhausted(name: String) =
        meters
            .counter(Resilience4jResilientEdge.BUDGET_EXHAUSTED_METER, "edge", name)
            .count()

    @Test
    fun `100 failing first attempts in one window make 10 retries and 90 calls end unavailable`() {
        val e = edge("access-svc", retry(RetryBudget(Duration.ofSeconds(10), 0.10)))
        val attempts = AtomicInteger()
        val outcomes = (1..100).map { e.callFailingFirstAttempt(attempts) }

        outcomes.count { it == "ok" } shouldBe 10
        outcomes.count { it == "unavailable" } shouldBe 90
        attempts.get() shouldBe 110 // 100 first attempts + 10 retries
        exhausted("access-svc") shouldBe 90.0
        // The 11th and later retries are the ones refused: the retried calls are the 10th, 20th, ... 100th.
        outcomes.indices.filter { outcomes[it] == "ok" } shouldBe (9..99 step 10).toList()
    }

    @Test
    fun `after the window passes a retry is taken again`() {
        val e = edge("access-svc-window", retry(RetryBudget(Duration.ofSeconds(10), 0.10)))
        val attempts = AtomicInteger()
        repeat(100) { e.callFailingFirstAttempt(attempts) }
        clock.now = clock.now.plusSeconds(11)
        // One call alone gives a ratio of 0.1 attempts: a lone call cannot retry, nine more make the tenth.
        val outcomes = (1..10).map { e.callFailingFirstAttempt(AtomicInteger()) }

        outcomes.last() shouldBe "ok"
        outcomes.dropLast(1).all { it == "unavailable" } shouldBe true
    }

    @Test
    fun `an edge without a budget retries every failed call once`() {
        val e = edge("tenancy-svc", retry(null))
        val attempts = AtomicInteger()
        val outcomes = (1..100).map { e.callFailingFirstAttempt(attempts) }

        outcomes.all { it == "ok" } shouldBe true
        attempts.get() shouldBe 200
        exhausted("tenancy-svc") shouldBe 0.0
    }

    @Test
    fun `an edge with no retry makes no retry`() {
        val e = edge("no-retry", null)
        val attempts = AtomicInteger()
        e.callFailingFirstAttempt(attempts) shouldBe "unavailable"
        attempts.get() shouldBe 1
    }

    @Test
    fun `a call that fails on every attempt does not spend budget on its last attempt`() {
        val e = edge("always-failing", retry(RetryBudget(Duration.ofSeconds(10), 1.0)))
        val attempts = AtomicInteger()
        repeat(10) {
            e.call {
                attempts.incrementAndGet()
                throw ConnectException("down")
            } shouldBe "unavailable"
        }

        attempts.get() shouldBe 20
        exhausted("always-failing") shouldBe 0.0
    }

    @Test
    fun `an open breaker refuses fast without spending a retry`() {
        val policy =
            EdgePolicy(
                TimeoutPolicy(Duration.ofMillis(500)),
                retry(RetryBudget(Duration.ofSeconds(10), 1.0)),
                CircuitBreakerPolicy(50F, 2, Duration.ofSeconds(60), 1),
                BulkheadPolicy(16, Duration.ZERO),
            )
        val e = Resilience4jResilientEdge("breaker", policy, clock, meters = meters)
        val attempts = AtomicInteger()
        repeat(2) {
            e.call {
                attempts.incrementAndGet()
                throw ConnectException("down")
            }
        }
        val before = attempts.get()

        e.call {
            attempts.incrementAndGet()
            "ok"
        } shouldBe "refused"
        attempts.get() shouldBe before
        exhausted("breaker") shouldBe 0.0
    }

    @Test
    fun `the resolved policies of access-svc and tenancy-svc differ only by the budget`() {
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            PropertiesPropertySource(
                "defaults",
                PropertiesLoaderUtils.loadProperties(ClassPathResource("service-runtime-defaults.properties")),
            ),
        )
        val bound =
            Binder(ConfigurationPropertySources.get(env))
                .bind("service-runtime.resilience", Bindable.of(ResilienceProperties::class.java))
                .get()
        val access = bound.edges.getValue("access-svc").toPolicy()
        val tenancy = bound.edges.getValue("tenancy-svc").toPolicy()

        val accessRetry = checkNotNull(access.retry)
        val tenancyRetry = checkNotNull(tenancy.retry)
        accessRetry shouldBe tenancyRetry.copy(budget = RetryBudget(Duration.ofSeconds(10), 0.10))
        access.copy(retry = null) shouldBe tenancy.copy(retry = null)
        tenancyRetry.budget shouldBe null
        access.timeout.timeout shouldBe Duration.ofMillis(500)
        accessRetry.maxAttempts shouldBe 2
        accessRetry.jitter shouldBe 0.5
    }

    private fun bind(vararg pairs: String): ResilienceProperties {
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            org.springframework.core.env.MapPropertySource(
                "test",
                pairs.associate { it.substringBefore('=') to it.substringAfter('=') },
            ),
        )
        return Binder(ConfigurationPropertySources.get(env))
            .bind("service-runtime.resilience", Bindable.of(ResilienceProperties::class.java))
            .get()
    }

    private val p = "service-runtime.resilience.edges.e"

    @Test
    fun `binding fails when only one budget setting is set`() {
        listOf("$p.retry-budget-window=10s", "$p.retry-budget-ratio=0.1").forEach { one ->
            val failure = runCatching { bind("$p.retry.max-attempts=2", one) }.exceptionOrNull()
            (failure != null) shouldBe true
        }
    }

    @Test
    fun `binding fails when a budget is set on an edge without a retry`() {
        val failure = runCatching { bind("$p.retry-budget-window=10s", "$p.retry-budget-ratio=0.1") }.exceptionOrNull()
        (failure != null) shouldBe true
        bind("$p.retry.max-attempts=2", "$p.retry-budget-window=10s", "$p.retry-budget-ratio=0.1")
            .edges
            .getValue("e")
            .toPolicy()
            .retry
            ?.budget shouldBe RetryBudget(Duration.ofSeconds(10), 0.1)
    }

    @Test
    fun `the defaults file carries the two budget keys on access-svc only`() {
        val props = PropertiesLoaderUtils.loadProperties(ClassPathResource("service-runtime-defaults.properties"))
        val budgetKeys = props.stringPropertyNames().filter { "retry-budget" in it }.toSet()
        budgetKeys shouldBe
            setOf(
                "service-runtime.resilience.edges.access-svc.retry-budget-window",
                "service-runtime.resilience.edges.access-svc.retry-budget-ratio",
            )
        props.getProperty("service-runtime.resilience.edges.access-svc.retry-budget-window") shouldBe "10s"
        props.getProperty("service-runtime.resilience.edges.access-svc.retry-budget-ratio") shouldBe "0.10"
        props.stringPropertyNames().none { it.startsWith("service" + "runtime" + ".edge.") } shouldBe true
    }
}
