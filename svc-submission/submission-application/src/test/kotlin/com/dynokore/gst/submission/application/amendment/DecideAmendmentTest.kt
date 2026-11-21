package com.dynokore.gst.submission.application.amendment

import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome
import com.dynokore.gst.submission.domain.amendment.AmendmentOutcomeRule
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.routing.ChangeClass
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DecideAmendmentTest {
    private val use = DecideAmendment()
    private val names = ChangeClass.entries.filter { it != ChangeClass.UNKNOWN }

    private fun decided(
        c: ChangeClass,
        t: Boolean,
    ) = AmendmentDecisionResult.Decided(AmendmentOutcomeRule.decide(c, t))

    @Test
    fun `INV-AC-26664 each class after an acknowledged version gives the transmitted outcome`() {
        names.forEach { use.decide(listOf(ACKNOWLEDGED), it.name) shouldBe decided(it, true) }
    }

    @Test
    fun `INV-AC-26664 each class after a ready version gives the not transmitted outcome`() {
        names.forEach { use.decide(listOf(READY), it.name) shouldBe decided(it, false) }
    }

    @Test
    fun `INV-AC-26664 a rejected earlier version is not transmitted`() {
        use.decide(listOf(REJECTED), "MONETARY") shouldBe AmendmentDecisionResult.Decided(AmendmentOutcome.Supersede)
    }

    @Test
    fun `INV-AC-26664 any transmitted earlier version makes the flag true`() {
        use.decide(listOf(READY, ACKNOWLEDGED), "MONETARY") shouldBe
            AmendmentDecisionResult.Decided(AmendmentOutcome.Hold("SUB-015"))
    }

    @Test
    fun `INV-AC-26664 no states and no class is no amendment`() {
        use.decide(emptyList(), null) shouldBe AmendmentDecisionResult.NoAmendment
    }

    @Test
    fun `INV-AC-26664 states and no class is undecided CLASS_ABSENT`() {
        use.decide(listOf(ACKNOWLEDGED), null) shouldBe AmendmentDecisionResult.Undecided(UndecidedReason.CLASS_ABSENT)
    }

    @Test
    fun `INV-AC-26664 a class without an earlier version is undecided`() {
        use.decide(emptyList(), "MONETARY") shouldBe
            AmendmentDecisionResult.Undecided(UndecidedReason.CLASS_WITHOUT_EARLIER_VERSION)
    }

    @Test
    fun `INV-AC-26664 a class text outside the five names is refused`() {
        listOf("UNKNOWN", "monetary", "MONETARY ", "").forEach {
            assertThrows<IllegalArgumentException> { use.decide(listOf(ACKNOWLEDGED), it) }
        }
    }
}
