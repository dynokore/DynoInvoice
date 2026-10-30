package com.dynokore.gst.submission.domain.backfill

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BackfillReconcilerTest {
    private val sample =
        listOf(
            BackfillCandidate("d1", "P1", "SUBMITTABLE", false),
            BackfillCandidate("d2", "P1", "PRE_OBLIGATION", false),
            BackfillCandidate("d3", "P2", "SUBMITTABLE", true),
            BackfillCandidate("d4", "P2", "NOT_SUBMITTABLE", false),
            BackfillCandidate("d5", "P2", "SUBMITTABLE", false),
        )

    @Test
    fun `INV-AC-13352 five documents in two periods reconcile to found 5 in scope 3 to be submitted 2`() {
        BackfillReconciler.reconcile(sample) shouldBe
            BackfillReconciliation(
                found = 5,
                duplicates = 0,
                inScope = 3,
                notSubmittable = 1,
                preObligation = 1,
                unrecognised = 0,
                alreadySubmitted = 1,
                toBeSubmitted = listOf("d1", "d5"),
                periods = listOf(PeriodEffect("P1", 2, 1), PeriodEffect("P2", 3, 1)),
            )
    }

    @Test
    fun `INV-AC-13352 an empty list gives all zeros`() {
        BackfillReconciler.reconcile(emptyList()) shouldBe
            BackfillReconciliation(0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList())
    }

    @ParameterizedTest(name = "INV-AC-13352 verdict [{0}] is unrecognised and never submitted")
    @ValueSource(strings = ["submittable", "SUBMITTABLE ", ""])
    fun `INV-AC-13352 inexact verdicts are unrecognised`(verdict: String) {
        val r = BackfillReconciler.reconcile(listOf(BackfillCandidate("d1", "P1", verdict, false)))
        r.unrecognised shouldBe 1
        r.inScope shouldBe 0
        r.toBeSubmitted shouldBe emptyList()
    }

    @Test
    fun `INV-AC-13352 an identical repeat counts once as a duplicate`() {
        val r = BackfillReconciler.reconcile(sample + sample.first())
        r.found shouldBe 5
        r.duplicates shouldBe 1
    }

    @Test
    fun `INV-AC-13352 a conflicting repeat throws`() {
        shouldThrow<IllegalArgumentException> {
            BackfillReconciler.reconcile(sample + BackfillCandidate("d1", "P2", "SUBMITTABLE", false))
        }
        shouldThrow<IllegalArgumentException> {
            BackfillReconciler.reconcile(sample + BackfillCandidate("d1", "P1", "SUBMITTABLE", true))
        }
    }

    @Test
    fun `INV-AC-13352 a blank docUuid or periodRef throws`() {
        shouldThrow<IllegalArgumentException> {
            BackfillReconciler.reconcile(listOf(BackfillCandidate(" ", "P1", "SUBMITTABLE", false)))
        }
        shouldThrow<IllegalArgumentException> {
            BackfillReconciler.reconcile(listOf(BackfillCandidate("d1", "", "SUBMITTABLE", false)))
        }
    }
}
