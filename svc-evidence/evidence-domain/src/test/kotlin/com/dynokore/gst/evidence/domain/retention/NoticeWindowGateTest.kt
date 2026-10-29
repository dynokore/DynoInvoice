package com.dynokore.gst.evidence.domain.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NoticeWindowGateTest {
    private val notified = Instant.parse("2026-01-01T00:00:00Z")
    private val days90 = NoticeWindow(90)

    private fun decide(
        window: NoticeWindow = days90,
        hold: Boolean = false,
        after: Duration,
    ) = NoticeWindowGate.decide(notified, window, hold, notified.plus(after))

    @Test
    fun `INV-AC-2488 day 89 is refused because the window is open`() {
        decide(after = Duration.ofDays(89)) shouldBe
            DeletionGate.WindowOpen(Instant.parse("2026-04-01T00:00:00Z"))
    }

    @Test
    fun `INV-AC-2488 day 91 may proceed to co-approval`() {
        decide(after = Duration.ofDays(91)) shouldBe DeletionGate.MayProceedToCoApproval
    }

    @Test
    fun `now equal to closesAt is still open`() {
        decide(after = Duration.ofDays(90)) shouldBe DeletionGate.WindowOpen(notified.plus(Duration.ofDays(90)))
    }

    @Test
    fun `one second after closesAt may proceed`() {
        decide(after = Duration.ofDays(90).plusSeconds(1)) shouldBe DeletionGate.MayProceedToCoApproval
    }

    @Test
    fun `a hold on day 10 is in force`() {
        decide(hold = true, after = Duration.ofDays(10)) shouldBe DeletionGate.HoldInForce
    }

    @Test
    fun `a hold on day 91 is in force`() {
        decide(hold = true, after = Duration.ofDays(91)) shouldBe DeletionGate.HoldInForce
    }

    @Test
    fun `now before the notice is open`() {
        decide(after = Duration.ofDays(-1)) shouldBe DeletionGate.WindowOpen(notified.plus(Duration.ofDays(90)))
    }

    @Test
    fun `a 365 day window is still open on day 91`() {
        decide(window = NoticeWindow(365), after = Duration.ofDays(91)) shouldBe
            DeletionGate.WindowOpen(notified.plus(Duration.ofDays(365)))
    }

    @Test
    fun `a 1 day window is open at 24 hours`() {
        decide(window = NoticeWindow(1), after = Duration.ofHours(24)) shouldBe
            DeletionGate.WindowOpen(notified.plus(Duration.ofDays(1)))
    }

    @Test
    fun `a 1 day window may proceed at 24 hours and 1 second`() {
        decide(window = NoticeWindow(1), after = Duration.ofHours(24).plusSeconds(1)) shouldBe
            DeletionGate.MayProceedToCoApproval
    }

    @Test
    fun `a window of 0 days is rejected`() {
        shouldThrow<IllegalArgumentException> { NoticeWindow(0) }
    }

    @Test
    fun `a window of -1 days is rejected`() {
        shouldThrow<IllegalArgumentException> { NoticeWindow(-1) }
    }
}
