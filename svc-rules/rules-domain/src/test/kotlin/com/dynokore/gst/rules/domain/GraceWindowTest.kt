package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.DeploymentForm
import com.dynokore.gst.rules.domain.pack.GraceDays
import com.dynokore.gst.rules.domain.pack.GraceDecision
import com.dynokore.gst.rules.domain.pack.GraceEnd
import com.dynokore.gst.rules.domain.pack.GraceWindow
import com.dynokore.gst.rules.domain.pack.NoGraceWindow
import com.dynokore.gst.rules.domain.pack.ReviewPolicy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class GraceWindowTest {
    private val effectiveFrom = LocalDate.of(2027, 1, 1)
    private val max = GraceDays(14)
    private val saas = DeploymentForm.SAAS
    private val review = ReviewPolicy.REVIEW_IN_GRACE_WINDOW

    private fun end(
        deliveredOn: LocalDate,
        stored: GraceDays? = null,
    ): LocalDate = (GraceWindow.endsOn(saas, deliveredOn, effectiveFrom, stored, max) as GraceEnd.Ends).on

    private fun decide(
        policy: ReviewPolicy,
        reviewed: Boolean,
        today: LocalDate,
        deliveredOn: LocalDate = effectiveFrom.minusDays(20),
        form: DeploymentForm = saas,
    ): GraceDecision = GraceWindow.decide(form, policy, deliveredOn, effectiveFrom, null, max, reviewed, today)

    @Test
    fun `INV-AC-13136 stored longer than maximum resolves to maximum`() {
        GraceWindow.effective(GraceDays(30), max) shouldBe GraceDays(14)
    }

    @Test
    fun `INV-AC-13136 null stored resolves to maximum and shorter stored is kept`() {
        GraceWindow.effective(null, max) shouldBe GraceDays(14)
        GraceWindow.effective(GraceDays(5), max) shouldBe GraceDays(5)
    }

    @Test
    fun `INV-AC-13136 delivery 10 days before effective date ends on the effective date`() {
        end(effectiveFrom.minusDays(10), GraceDays(30)) shouldBe effectiveFrom
    }

    @Test
    fun `INV-AC-13136 delivery 20 days before ends 14 days after delivery`() {
        val delivered = effectiveFrom.minusDays(20)
        end(delivered, GraceDays(30)) shouldBe delivered.plusDays(14)
    }

    @Test
    fun `INV-AC-13137 private form has no grace window for every policy`() {
        val delivered = effectiveFrom.minusDays(20)
        GraceWindow.endsOn(DeploymentForm.PRIVATE, delivered, effectiveFrom, null, max) shouldBe NoGraceWindow
        for (policy in ReviewPolicy.entries) {
            for (reviewed in listOf(true, false)) {
                for (today in listOf(delivered, effectiveFrom, effectiveFrom.plusDays(1))) {
                    decide(policy, reviewed, today, delivered, DeploymentForm.PRIVATE) shouldBe NoGraceWindow
                }
            }
        }
    }

    @Test
    fun `INV-AC-13138 notice only stays open before effective date and activates unreviewed on it`() {
        for (reviewed in listOf(true, false)) {
            decide(ReviewPolicy.NOTICE_ONLY, reviewed, effectiveFrom.minusDays(1)) shouldBe
                GraceDecision.Open(effectiveFrom)
            decide(ReviewPolicy.NOTICE_ONLY, reviewed, effectiveFrom) shouldBe GraceDecision.ActivateUnreviewed
        }
    }

    @Test
    fun `INV-AC-13138 default policy unreviewed one day before the end is open`() {
        val delivered = effectiveFrom.minusDays(20)
        val windowEnd = delivered.plusDays(14)
        decide(review, false, windowEnd.minusDays(1), delivered) shouldBe GraceDecision.Open(windowEnd)
    }

    @Test
    fun `INV-AC-13139 unreviewed on the window end differs from reviewed`() {
        val delivered = effectiveFrom.minusDays(20)
        val windowEnd = delivered.plusDays(14)
        val unreviewed = decide(review, false, windowEnd, delivered)
        val reviewed = decide(review, true, windowEnd, delivered)
        unreviewed shouldBe GraceDecision.ActivateUnreviewed
        reviewed shouldBe GraceDecision.ActivateReviewed
        unreviewed shouldNotBe reviewed
    }

    @Test
    fun `INV-AC-13139 reviewed one day after delivery is activated reviewed`() {
        val delivered = effectiveFrom.minusDays(20)
        decide(review, true, delivered.plusDays(1), delivered) shouldBe GraceDecision.ActivateReviewed
    }

    @Test
    fun `INV-AC-13139 unreviewed activates on a window end earlier than the effective date`() {
        val delivered = effectiveFrom.minusDays(20)
        val windowEnd = delivered.plusDays(14)
        (windowEnd < effectiveFrom) shouldBe true
        decide(review, false, windowEnd, delivered) shouldBe GraceDecision.ActivateUnreviewed
    }

    @Test
    fun `grace days below one are refused`() {
        assertThrows<IllegalArgumentException> { GraceDays(0) }
        assertThrows<IllegalArgumentException> { GraceDays(-1) }
    }

    @Test
    fun `delivery after the effective date ends on the effective date`() {
        end(effectiveFrom.plusDays(3)) shouldBe effectiveFrom
    }

    @Test
    fun `review policy has exactly two members`() {
        ReviewPolicy.entries.size shouldBe 2
    }

    @Test
    fun `sealed results are exhaustive`() {
        val d: GraceDecision = decide(review, false, effectiveFrom)
        val label =
            when (d) {
                is GraceDecision.Open -> "open"
                GraceDecision.ActivateReviewed -> "reviewed"
                GraceDecision.ActivateUnreviewed -> "unreviewed"
                NoGraceWindow -> "none"
            }
        label shouldBe "unreviewed"
    }
}
