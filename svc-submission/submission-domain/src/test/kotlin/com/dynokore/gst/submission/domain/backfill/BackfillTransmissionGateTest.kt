package com.dynokore.gst.submission.domain.backfill

import com.dynokore.gst.submission.domain.backfill.BackfillGateDecision.Hold
import com.dynokore.gst.submission.domain.backfill.BackfillGateDecision.Transmit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackfillTransmissionGateTest {
    private val reconciliation =
        BackfillReconciler.reconcile(
            listOf(
                BackfillCandidate("d1", "P1", "SUBMITTABLE", false),
                BackfillCandidate("d2", "P1", "PRE_OBLIGATION", false),
                BackfillCandidate("d3", "P2", "SUBMITTABLE", true),
                BackfillCandidate("d4", "P2", "NOT_SUBMITTABLE", false),
                BackfillCandidate("d5", "P2", "SUBMITTABLE", false),
            ),
        )

    private fun approval(
        docs: Set<String> = setOf("d5", "d1"),
        authorised: Boolean = true,
        approver: String = "u-approver",
        requester: String = "u-requester",
    ) = BackfillApproval(approver, requester, authorised, docs)

    @Test
    fun `INV-AC-13352 no approval holds`() {
        BackfillTransmissionGate.decide(reconciliation, null) shouldBe Hold(BackfillHoldReason.NO_APPROVAL)
    }

    @Test
    fun `INV-AC-13352 an unauthorised approval holds`() {
        BackfillTransmissionGate.decide(reconciliation, approval(authorised = false)) shouldBe
            Hold(BackfillHoldReason.NOT_AUTHORISED)
    }

    @Test
    fun `INV-AC-13352 the requester's own approval holds`() {
        BackfillTransmissionGate.decide(reconciliation, approval(approver = "u1", requester = "u1")) shouldBe
            Hold(BackfillHoldReason.SAME_PERSON)
    }

    @Test
    fun `INV-AC-13352 an approval of another document set holds`() {
        BackfillTransmissionGate.decide(reconciliation, approval(docs = setOf("d1"))) shouldBe
            Hold(BackfillHoldReason.STALE_APPROVAL)
        BackfillTransmissionGate.decide(reconciliation, approval(docs = setOf("d1", "d5", "d6"))) shouldBe
            Hold(BackfillHoldReason.STALE_APPROVAL)
    }

    @Test
    fun `INV-AC-13352 a valid approval releases exactly the reconciled documents`() {
        BackfillTransmissionGate.decide(reconciliation, approval()) shouldBe Transmit(listOf("d1", "d5"))
    }

    @Test
    fun `INV-AC-13352 nothing to submit holds even with a valid approval`() {
        val empty = BackfillReconciler.reconcile(emptyList())
        BackfillTransmissionGate.decide(empty, approval(docs = emptySet())) shouldBe
            Hold(BackfillHoldReason.NOTHING_TO_SUBMIT)
    }

    @Test
    fun `INV-AC-13352 only Transmit carries a document list`() {
        Hold::class.java.methods
            .map { it.name }
            .contains("getDocUuids") shouldBe false
        Transmit::class.java.methods
            .map { it.name }
            .contains("getDocUuids") shouldBe true
    }

    @Test
    fun `INV-AC-13352 a blank user id throws`() {
        shouldThrow<IllegalArgumentException> {
            BackfillTransmissionGate.decide(reconciliation, approval(approver = " "))
        }
        shouldThrow<IllegalArgumentException> {
            BackfillTransmissionGate.decide(reconciliation, approval(requester = ""))
        }
    }
}
