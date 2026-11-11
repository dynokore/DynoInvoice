package com.dynokore.gst.consoleBff.domain.ratelimit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-6924 at the limiter: the defaults, the tenant, the ready replicas and the API-30 fields. */
class ConsoleRateLimiterTest {
    private class Moving(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val start = Instant.parse("2026-11-11T10:00:00Z")
    private val clock = Moving(start)

    private fun limiter(
        settings: ConsoleRateLimitSettings = ConsoleRateLimitSettings(),
        replicas: Int = 1,
    ) = ConsoleRateLimiter(settings, replicas, clock)

    @Test
    fun `INV-AC-6924 the 301st request of one principal within 60 seconds is refused with the console policy`() {
        val limiter = limiter()
        repeat(300) { limiter.check("p-1", "t-1").allowed shouldBe true }
        val refused = limiter.check("p-1", "t-1")
        refused.allowed shouldBe false
        refused.policy("console").allowed shouldBe false
        refused.policy("tenant").allowed shouldBe true
        refused.retryAfter shouldBe Duration.ofSeconds(60)
        RateLimitFields.of(refused)["RateLimit-Policy"] shouldBe "\"console\";q=300;w=60, \"tenant\";q=3000;w=60"
        RateLimitFields.of(refused)["RateLimit"] shouldBe "\"console\";r=0;t=60, \"tenant\";r=2699;t=60"
        RateLimitFields.of(refused)["Retry-After"] shouldBe "60"
    }

    @Test
    fun `INV-AC-6924 the 3001st request of one tenant across principals is refused with the tenant policy`() {
        val limiter = limiter()
        repeat(3_000) { limiter.check("p-${it / 100}", "t-1").allowed shouldBe true }
        val refused = limiter.check("p-new", "t-1")
        refused.allowed shouldBe false
        refused.policy("tenant").allowed shouldBe false
        refused.policy("console").allowed shouldBe true
        limiter.check("p-new", "t-2").allowed shouldBe true
    }

    @Test
    fun `INV-AC-6924 a refused request is not counted and the window slides`() {
        val limiter = limiter(ConsoleRateLimitSettings(perPrincipal = 2))
        limiter.check("p", "t").allowed shouldBe true
        clock.now = start.plusSeconds(30)
        limiter.check("p", "t").allowed shouldBe true
        repeat(5) { limiter.check("p", "t").allowed shouldBe false }
        val refused = limiter.check("p", "t")
        refused.retryAfter shouldBe Duration.ofSeconds(30)
        clock.now = start.plusSeconds(60)
        limiter.check("p", "t").allowed shouldBe true
        limiter.check("p", "t").allowed shouldBe false
    }

    @Test
    fun `INV-AC-6924 a lowered setting takes effect and a per-tenant override applies to that tenant only`() {
        val settings =
            ConsoleRateLimitSettings(
                perPrincipal = 5,
                perTenant = 7,
                tenantOverrides =
                    mapOf("t-big" to ConsoleRateLimitSettings.TenantOverride(perPrincipal = 20, perTenant = 50)),
            )
        val limiter = limiter(settings)
        repeat(5) { limiter.check("p", "t-1").allowed shouldBe true }
        limiter.check("p", "t-1").allowed shouldBe false
        repeat(20) { limiter.check("p", "t-big").allowed shouldBe true }
        limiter.check("p", "t-big").allowed shouldBe false
        limiter.check("q", "t-1").allowed shouldBe true
        limiter.check("r", "t-1").allowed shouldBe true // the 7th admitted request of tenant t-1
        limiter.check("s", "t-1").allowed shouldBe false // the 8th: beyond the lowered tenant limit of 7
    }

    @Test
    fun `INV-AC-6924 with two ready replicas each admits half and the divided limit is rounded down, at least 1`() {
        val two = limiter(replicas = 2)
        repeat(150) { two.check("p", "t").allowed shouldBe true }
        two.check("p", "t").allowed shouldBe false
        two.check("p", "t").policy("console").quota shouldBe 150
        two.check("p", "t").policy("tenant").quota shouldBe 1_500
        limiter(ConsoleRateLimitSettings(perPrincipal = 5), 2).check("p", "t").policy("console").quota shouldBe 2
        limiter(ConsoleRateLimitSettings(perPrincipal = 1), 4).check("p", "t").policy("console").quota shouldBe 1
    }

    @Test
    fun `INV-AC-6924 the counter key cannot be forged across tenant and principal ids`() {
        val limiter = limiter(ConsoleRateLimitSettings(perPrincipal = 1))
        limiter.check("b/c", "a").allowed shouldBe true
        limiter.check("c", "a/b").allowed shouldBe true
        limiter.check("a", "b").allowed shouldBe true
    }

    @Test
    fun `INV-AC-6924 refuses a limit below 1, a replica count below 1 and evicts idle counters`() {
        shouldThrow<IllegalArgumentException> { ConsoleRateLimitSettings(perPrincipal = 0) }
        shouldThrow<IllegalArgumentException> { ConsoleRateLimitSettings(perTenant = -1) }
        shouldThrow<IllegalArgumentException> { ConsoleRateLimitSettings.TenantOverride(perTenant = 0) }
        shouldThrow<IllegalArgumentException> { limiter(replicas = 0) }
        val limiter = limiter(ConsoleRateLimitSettings(perPrincipal = 1))
        limiter.check("p", "t").allowed shouldBe true
        clock.now = start.plusSeconds(61)
        limiter.evictIdle()
        limiter.check("p", "t").allowed shouldBe true
    }
}
