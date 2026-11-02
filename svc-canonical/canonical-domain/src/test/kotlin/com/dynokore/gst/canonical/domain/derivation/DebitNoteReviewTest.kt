package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.derivation.DebitNoteReviewOption.HOLD_FOR_REVIEW
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.DOCUMENT_FUNCTION
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Complete
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.HeldBy
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DebitNoteReviewTest {
    private val gateCode = Regex("^[A-Z]{2,10}-[0-9]{3}$")

    @Test
    fun `INV-AC-16158 all twelve pairs with released false and true give the expected outcome`() {
        var cases = 0
        for (option in DebitNoteReviewOption.entries) {
            for (function in DocumentFunction.entries) {
                for (released in listOf(false, true)) {
                    val expected: StepOutcome =
                        if (option == HOLD_FOR_REVIEW && function == DocumentFunction.DEBIT_NOTE && !released) {
                            HeldBy("ING-024")
                        } else {
                            Complete
                        }
                    DebitNoteReview.outcome(option, function, released) shouldBe expected
                    cases++
                }
            }
        }
        cases shouldBe 24
    }

    @Test
    fun `INV-AC-16158 the gate holds with ING-024 and proceeds once released`() {
        fun gate(released: Boolean): GateDecision {
            val held = DebitNoteReview.outcome(HOLD_FOR_REVIEW, DocumentFunction.DEBIT_NOTE, released)
            val outcomes =
                DerivationStep.entries.associateWith<DerivationStep, StepOutcome> { Complete } +
                    (DOCUMENT_FUNCTION to held)
            return DerivationGate.decide(outcomes)
        }
        DerivationStep.entries.size shouldBe 8
        gate(false) shouldBe GateDecision.Held(listOf("ING-024"))
        gate(true) shouldBe GateDecision.Proceed
    }

    @Test
    fun `INV-AC-16158 the option and function enums have the pinned members`() {
        DebitNoteReviewOption.entries shouldBe listOf(DebitNoteReviewOption.PACK_RULE, HOLD_FOR_REVIEW)
        DocumentFunction.entries.size shouldBe 6
    }

    @Test
    fun `INV-AC-16158 the code constant is ING-024 and matches the gate pattern`() {
        DEBIT_NOTE_REVIEW_CODE shouldBe "ING-024"
        gateCode.matches(DEBIT_NOTE_REVIEW_CODE) shouldBe true
    }
}
