package com.dynokore.gst.gatewayauthz.ratelimit

import com.dynokore.gst.gatewayauthz.MutableClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class ProtectiveRateLimiterTest {
    private val clock = MutableClock()

    @Test
    fun `INV-AC-4052 an ingest principal's 601st request in 60 seconds is refused with Retry-After`() {
        val limiter = ProtectiveRateLimiter(RateLimitSettings(), readyReplicas = 1, clock = clock)
        val served =
            (1..600).map {
                clock.advance(Duration.ofMillis(50))
                limiter.checkMachine("ingest", "PRN-00088", null)
            }
        served.all { it.allowed } shouldBe true
        served.first().policy("ingest").quota shouldBe 600
        served.last().policy("ingest").remaining shouldBe 0

        clock.advance(Duration.ofMillis(50))
        val refused = limiter.checkMachine("ingest", "PRN-00088", null)
        refused.allowed shouldBe false
        refused.retryAfter shouldBe Duration.ofSeconds(30)

        RateLimitFields.of(refused) shouldBe
            mapOf(
                "RateLimit-Policy" to "\"ingest\";q=600;w=60",
                "RateLimit" to "\"ingest\";r=0;t=30",
                "Retry-After" to "30",
            )
    }

    @Test
    fun `the fields list every policy that applies and Retry-After is the later reset of the exhausted ones`() {
        val settings = RateLimitSettings(machinePerPrincipal = 2, machinePerTenant = 2)
        val limiter = ProtectiveRateLimiter(settings, readyReplicas = 1, clock = clock)
        limiter.checkMachine("ingest", "a", "t").allowed shouldBe true
        clock.advance(Duration.ofSeconds(10))
        val second = limiter.checkMachine("ingest", "a", "t")
        RateLimitFields.of(second) shouldBe
            mapOf(
                "RateLimit-Policy" to "\"ingest\";q=2;w=60, \"tenant\";q=2;w=60",
                "RateLimit" to "\"ingest\";r=0;t=50, \"tenant\";r=0;t=50",
            )
        clock.advance(Duration.ofSeconds(10))
        val refused = limiter.checkMachine("ingest", "a", "t")
        refused.policies.none { it.allowed } shouldBe true
        refused.retryAfter shouldBe Duration.ofSeconds(40)
        RateLimitFields.of(refused)["Retry-After"] shouldBe "40"
    }

    @Test
    fun `a refused request is not counted and the window slides`() {
        val limiter = ProtectiveRateLimiter(RateLimitSettings(signedLinkPerLink = 2), readyReplicas = 1, clock = clock)
        limiter.checkSignedLink("link-1").allowed shouldBe true
        clock.advance(Duration.ofSeconds(30))
        limiter.checkSignedLink("link-1").allowed shouldBe true
        limiter.checkSignedLink("link-1").allowed shouldBe false

        clock.advance(Duration.ofSeconds(30))
        limiter.checkSignedLink("link-1").allowed shouldBe true
        limiter.checkSignedLink("link-2").allowed shouldBe true
    }

    @Test
    fun `the tenant limit binds across the tenant's principals`() {
        val settings = RateLimitSettings(machinePerPrincipal = 3, machinePerTenant = 4)
        val limiter = ProtectiveRateLimiter(settings, readyReplicas = 1, clock = clock)
        repeat(3) { limiter.checkMachine("ingest", "a", "t").allowed shouldBe true }
        limiter.checkMachine("ingest", "b", "t").allowed shouldBe true

        val refused = limiter.checkMachine("ingest", "c", "t")
        refused.allowed shouldBe false
        refused.policy("ingest").allowed shouldBe true
        refused.policy("tenant").allowed shouldBe false
        limiter.checkMachine("ingest", "c", "other").allowed shouldBe true
        limiter.checkMachine("ingest", "d", null).policies.map { it.name } shouldBe listOf("ingest")
    }

    @Test
    fun `the effective limit is the configured one divided by the ready replica count`() {
        val limiter = ProtectiveRateLimiter(RateLimitSettings(), readyReplicas = 3, clock = clock)
        limiter.checkMachine("ingest", "p", "t").policy("tenant").quota shouldBe 400
        limiter.checkSignedLink("link-1").policy("signed-link").quota shouldBe 10
        limiter.checkApNotification("adapter-1").policy("ap-callback").quota shouldBe 200
        ProtectiveRateLimiter(RateLimitSettings(signedLinkPerLink = 2), 3, clock)
            .checkSignedLink("l")
            .policies
            .single()
            .quota shouldBe 1
    }

    @Test
    fun `INV-AC-4053 the signed-link limit is refused above 30, and at 10 the 11th request on one link is refused`() {
        shouldThrow<IllegalArgumentException> { RateLimitSettings(signedLinkPerLink = 60) }

        val limiter = ProtectiveRateLimiter(RateLimitSettings(signedLinkPerLink = 10), readyReplicas = 1, clock = clock)
        repeat(10) { limiter.checkSignedLink("link-1").allowed shouldBe true }
        val refused = limiter.checkSignedLink("link-1")
        refused.allowed shouldBe false
        RateLimitFields.of(refused)["Retry-After"] shouldBe "60"
    }

    @Test
    fun `the API limits may be raised`() {
        RateLimitSettings(machinePerPrincipal = 6_000, machinePerTenant = 30_000).machinePerPrincipal shouldBe 6_000
    }

    @Test
    fun `idle counters are evicted`() {
        val limiter = ProtectiveRateLimiter(RateLimitSettings(signedLinkPerLink = 1), readyReplicas = 1, clock = clock)
        limiter.checkSignedLink("link-1").allowed shouldBe true
        clock.advance(Duration.ofSeconds(61))
        limiter.evictIdle()
        limiter.checkSignedLink("link-1").allowed shouldBe true
    }
}
