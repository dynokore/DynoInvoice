package com.dynokore.gst.notification.domain.webhook

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class WebhookRetryWindowTest {
    private val max = RetryWindow(168)
    private val first = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `INV-AC-2491 window below the operator maximum is accepted`() {
        WebhookRetryWindow.set(RetryWindow(100), RetryWindow(72), max) shouldBe
            RetryWindowDecision.Accepted(RetryWindow(100))
    }

    @Test
    fun `INV-AC-2491 window above the operator maximum is refused and the subscription keeps its window`() {
        WebhookRetryWindow.set(RetryWindow(200), RetryWindow(100), max) shouldBe
            RetryWindowDecision.Refused("POL-013", RetryWindow(100))
    }

    @Test
    fun `requested equal to the maximum is accepted and one above is refused`() {
        WebhookRetryWindow.set(RetryWindow(168), RetryWindow(72), max) shouldBe
            RetryWindowDecision.Accepted(RetryWindow(168))
        WebhookRetryWindow.set(RetryWindow(169), RetryWindow(72), max) shouldBe
            RetryWindowDecision.Refused("POL-013", RetryWindow(72))
    }

    @Test
    fun `effective window is capped at the operator maximum`() {
        WebhookRetryWindow.effective(RetryWindow(200), max) shouldBe RetryWindow(168)
        WebhookRetryWindow.effective(RetryWindow(100), max) shouldBe RetryWindow(100)
    }

    @Test
    fun `INV-AC-2490 retries stop after 72 hours and the delivery is dead-lettered`() {
        val stop = Instant.parse("2026-01-04T00:00:00Z")
        WebhookRetryWindow.next(first, RetryWindow(72), stop.minusSeconds(1)) shouldBe DeliveryRetry.Retry
        WebhookRetryWindow.next(first, RetryWindow(72), stop) shouldBe DeliveryRetry.DeadLetter
    }

    @Test
    fun `now before the first attempt is retry`() {
        WebhookRetryWindow.next(first, RetryWindow(72), first.minusSeconds(60)) shouldBe DeliveryRetry.Retry
    }

    @Test
    fun `a window of one hour retries at 59 minutes and dead-letters at one hour`() {
        WebhookRetryWindow.next(first, RetryWindow(1), first.plusSeconds(59 * 60)) shouldBe DeliveryRetry.Retry
        WebhookRetryWindow.next(first, RetryWindow(1), first.plusSeconds(3600)) shouldBe DeliveryRetry.DeadLetter
    }

    @Test
    fun `a window below one hour is rejected`() {
        shouldThrow<IllegalArgumentException> { RetryWindow(0) }
        shouldThrow<IllegalArgumentException> { RetryWindow(-1) }
    }
}
