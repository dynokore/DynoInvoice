package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-9180: calls are counted at a stub fallback with a movable fixed clock. */
class VerdictCachingAuthorityFallbackTest {
    private class MovableClock : Clock() {
        var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock()
    private val start = clock.now
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())
    private val meters = SimpleMeterRegistry()
    private val calls = AtomicInteger()
    private var answer: () -> FallbackResult<AuthorityVerdict> = { FallbackResult.Found(AuthorityVerdict(true)) }
    private val stub =
        ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
            calls.incrementAndGet()
            answer()
        }
    private val subject = VerdictCachingAuthorityFallback(stub, clock, meters, Duration.ofMillis(1000))
    private val deadline = Deadline.after(Duration.ofHours(1), clock)

    private fun at(ms: Long) {
        clock.now = start.plusMillis(ms)
    }

    private fun ask(
        tenant: TenantId = tenantA,
        principal: String = "p1",
        entityRef: String = "E1",
        action: String = "X",
    ) = subject.fetch(
        AuthorizationRequest(tenant, Principal(principal, PrincipalKind.HUMAN), entityRef, action),
        deadline,
    )

    private fun hits() = meters.counter(VerdictCachingAuthorityFallback.CACHE_HIT_METER).count()

    @Test
    fun `INV-AC-9180 the same question at t0 and t999 reaches the stub once and counts one hit`() {
        at(0)
        ask()
        at(999)
        ask() shouldBe FallbackResult.Found(AuthorityVerdict(true))
        calls.get() shouldBe 1
        hits() shouldBe 1.0
    }

    @Test
    fun `INV-AC-9180 at t1001 the question reaches the stub again`() {
        at(0)
        ask()
        at(1001)
        ask()
        calls.get() shouldBe 2
        hits() shouldBe 0.0
    }

    @Test
    fun `INV-AC-9180 a revocation at t500 is seen from the stub only after t1000`() {
        at(0)
        ask() shouldBe FallbackResult.Found(AuthorityVerdict(true))
        at(500)
        answer = { FallbackResult.Found(AuthorityVerdict(false)) }
        at(1000)
        ask() shouldBe FallbackResult.Found(AuthorityVerdict(true))
        at(1001)
        ask() shouldBe FallbackResult.Found(AuthorityVerdict(false))
        calls.get() shouldBe 2
    }

    @Test
    fun `INV-AC-9180 look-alike keys never hit`() {
        at(0)
        ask()
        ask(action = "Y")
        ask(entityRef = "E2")
        ask(tenant = tenantB)
        ask(principal = "p2")
        calls.get() shouldBe 5
        hits() shouldBe 0.0
        ask()
        calls.get() shouldBe 5
        hits() shouldBe 1.0
    }

    @Test
    fun `INV-AC-9180 Unavailable and NotFound are never stored`() {
        answer = { FallbackResult.Unavailable }
        ask()
        ask()
        answer = { FallbackResult.NotFound }
        ask()
        ask()
        calls.get() shouldBe 4
        subject.size shouldBe 0
        hits() shouldBe 0.0
    }

    @Test
    fun `INV-AC-9180 a Found answer after an Unavailable one is stored and then reused`() {
        answer = { FallbackResult.Unavailable }
        ask()
        answer = { FallbackResult.Found(AuthorityVerdict(true)) }
        ask()
        ask()
        calls.get() shouldBe 2
    }

    @Test
    fun `INV-AC-9180 the 10001st distinct key drops the oldest`() {
        repeat(10_000) { ask(entityRef = "E$it") }
        subject.size shouldBe 10_000
        ask(entityRef = "E10000")
        subject.size shouldBe 10_000
        val before = calls.get()
        ask(entityRef = "E0")
        calls.get() shouldBe before + 1
        ask(entityRef = "E10000")
        calls.get() shouldBe before + 1
    }

    @Test
    fun `INV-AC-9180 a ttl above 1000 ms or not above zero is refused`() {
        listOf(Duration.ofMillis(1001), Duration.ZERO, Duration.ofMillis(-1)).forEach {
            assertThrows<IllegalArgumentException> { VerdictCachingAuthorityFallback(stub, clock, meters, it) }
        }
        VerdictCachingAuthorityFallback(stub, clock, meters, Duration.ofMillis(1000))
    }
}
