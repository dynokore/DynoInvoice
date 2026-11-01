package com.dynokore.gst.ingestion.domain.pull

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class PullWindowPlannerTest {
    private val now = Instant.parse("2026-03-10T00:00:00Z")
    private val bounds = LookBackBounds(1, 60)
    private val watermark = PostingWatermark(Instant.parse("2026-03-08T00:00:00Z"))

    private fun plan(
        w: PostingWatermark? = watermark,
        days: Long = 7,
        b: LookBackBounds = bounds,
    ) = PullWindowPlanner.plan(w, days, b, now)

    private fun refused(reason: PullWindowRefusal) = PullWindow.Refused(reason)

    @Test
    fun `INV-AC-14106 look-back 7 starts a week before the watermark and ends at now`() {
        plan() shouldBe PullWindow.Planned(Instant.parse("2026-03-01T00:00:00Z"), now)
    }

    @Test
    fun `INV-AC-14106 both bounds are inclusive`() {
        plan(days = 1) shouldBe PullWindow.Planned(Instant.parse("2026-03-07T00:00:00Z"), now)
        plan(days = 60) shouldBe PullWindow.Planned(Instant.parse("2026-01-07T00:00:00Z"), now)
    }

    @Test
    fun `INV-AC-14106 look-back outside the bounds is refused`() {
        plan(days = 0) shouldBe refused(PullWindowRefusal.LOOK_BACK_BELOW_MINIMUM)
        plan(days = 61) shouldBe refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
    }

    @Test
    fun `INV-AC-14106 no watermark is refused`() {
        plan(w = null) shouldBe refused(PullWindowRefusal.NO_WATERMARK)
    }

    @Test
    fun `INV-AC-14106 watermark after now is refused and equal to now is planned`() {
        plan(w = PostingWatermark(now.plusSeconds(1))) shouldBe refused(PullWindowRefusal.WATERMARK_AFTER_NOW)
        plan(w = PostingWatermark(now)) shouldBe PullWindow.Planned(Instant.parse("2026-03-03T00:00:00Z"), now)
    }

    @Test
    fun `INV-AC-14106 look-back refusal comes before the watermark refusals`() {
        plan(w = null, days = 0) shouldBe refused(PullWindowRefusal.LOOK_BACK_BELOW_MINIMUM)
        plan(w = null, days = 61) shouldBe refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
        plan(w = PostingWatermark(now.plusSeconds(1)), days = 61) shouldBe
            refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
    }

    @Test
    fun `INV-AC-14106 no watermark is refused before a future watermark would be`() {
        plan(w = null, days = 7) shouldBe refused(PullWindowRefusal.NO_WATERMARK)
    }

    @Test
    fun `INV-AC-14106 bounds with minimum above maximum are rejected`() {
        assertThrows<IllegalArgumentException> { LookBackBounds(5, 3) }
    }

    @Test
    fun `INV-AC-14106 bounds are arguments not constants`() {
        val narrow = LookBackBounds(1, 10)
        plan(days = 61, b = narrow) shouldBe refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
        plan(days = 10, b = narrow) shouldBe PullWindow.Planned(Instant.parse("2026-02-26T00:00:00Z"), now)
        val fixed = LookBackBounds(7, 7)
        plan(days = 7, b = fixed) shouldBe PullWindow.Planned(Instant.parse("2026-03-01T00:00:00Z"), now)
        plan(days = 8, b = fixed) shouldBe refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
    }
}
