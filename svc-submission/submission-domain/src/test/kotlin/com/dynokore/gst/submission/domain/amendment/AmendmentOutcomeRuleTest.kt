package com.dynokore.gst.submission.domain.amendment

import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome.Hold
import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome.Reject
import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome.Resubmit
import com.dynokore.gst.submission.domain.amendment.AmendmentOutcome.Supersede
import com.dynokore.gst.submission.domain.routing.ChangeClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AmendmentOutcomeRuleTest {
    private fun decide(
        c: ChangeClass,
        t: Boolean,
    ) = AmendmentOutcomeRule.decide(c, t)

    @Test
    fun `INV-AC-26664 transmitted NON_MONETARY resubmits with nothing raised`() {
        decide(ChangeClass.NON_MONETARY, true) shouldBe Resubmit(emptyList())
    }

    @Test
    fun `INV-AC-26664 transmitted IDENTITY holds with SUB-016`() {
        decide(ChangeClass.IDENTITY, true) shouldBe Hold("SUB-016")
    }

    @Test
    fun `INV-AC-26664 transmitted PERIOD_SHIFTING resubmits raising PER-006`() {
        decide(ChangeClass.PERIOD_SHIFTING, true) shouldBe Resubmit(listOf("PER-006"))
    }

    @Test
    fun `INV-AC-26664 transmitted MONETARY holds with SUB-015 and not SUB-016`() {
        decide(ChangeClass.MONETARY, true) shouldBe Hold("SUB-015")
        decide(ChangeClass.MONETARY, true) shouldNotBe Hold("SUB-016")
    }

    @Test
    fun `INV-AC-26664 transmitted STRUCTURAL rejects with ING-012`() {
        decide(ChangeClass.STRUCTURAL, true) shouldBe Reject("ING-012")
    }

    @Test
    fun `INV-AC-26664 not transmitted every class except STRUCTURAL supersedes`() {
        listOf(ChangeClass.NON_MONETARY, ChangeClass.IDENTITY, ChangeClass.PERIOD_SHIFTING, ChangeClass.MONETARY)
            .forEach { decide(it, false) shouldBe Supersede }
        decide(ChangeClass.MONETARY, false) shouldNotBe Hold("SUB-015")
    }

    @Test
    fun `INV-AC-26664 not transmitted STRUCTURAL rejects with ING-012`() {
        decide(ChangeClass.STRUCTURAL, false) shouldBe Reject("ING-012")
    }

    @Test
    fun `INV-AC-26664 UNKNOWN is refused`() {
        assertThrows<IllegalArgumentException> { decide(ChangeClass.UNKNOWN, true) }
        assertThrows<IllegalArgumentException> { decide(ChangeClass.UNKNOWN, false) }
    }
}
