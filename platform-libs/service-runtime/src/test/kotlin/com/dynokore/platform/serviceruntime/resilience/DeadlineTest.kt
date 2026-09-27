package com.dynokore.platform.serviceruntime.resilience

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** FR-AH.7, API-29: the caller's own remaining budget, never a wall-clock read (CS-10). */
class DeadlineTest {
    private val now = Instant.parse("2026-09-27T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `after creates a deadline budget away from the given clock`() {
        val deadline = Deadline.after(Duration.ofSeconds(30), clock)
        deadline.remaining(clock) shouldBe Duration.ofSeconds(30)
    }

    @Test
    fun `after rejects a negative budget`() {
        shouldThrow<IllegalArgumentException> { Deadline.after(Duration.ofSeconds(-1), clock) }
    }

    @Test
    fun `remaining shrinks as the clock advances, and floors at zero rather than going negative`() {
        val deadline = Deadline.after(Duration.ofSeconds(10), clock)
        val laterClock = Clock.fixed(now.plusSeconds(4), ZoneOffset.UTC)
        val muchLaterClock = Clock.fixed(now.plusSeconds(50), ZoneOffset.UTC)

        deadline.remaining(laterClock) shouldBe Duration.ofSeconds(6)
        deadline.remaining(muchLaterClock) shouldBe Duration.ZERO
    }

    @Test
    fun `isExpired is false with time left and true once the budget is used up`() {
        val deadline = Deadline.after(Duration.ofSeconds(10), clock)
        val laterClock = Clock.fixed(now.plusSeconds(4), ZoneOffset.UTC)
        val expiredClock = Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC)

        deadline.isExpired(laterClock) shouldBe false
        deadline.isExpired(expiredClock) shouldBe true
    }

    @Test
    fun `ofRemainingMillis reconstructs a deadline with exactly that much left, coercing a negative value to zero`() {
        Deadline.ofRemainingMillis(150, clock).remaining(clock) shouldBe Duration.ofMillis(150)
        Deadline.ofRemainingMillis(-50, clock).remaining(clock) shouldBe Duration.ZERO
    }
}
