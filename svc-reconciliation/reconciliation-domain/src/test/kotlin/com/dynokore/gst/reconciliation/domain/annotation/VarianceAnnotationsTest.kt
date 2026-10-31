package com.dynokore.gst.reconciliation.domain.annotation

import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Accepted
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Refused
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationDecision.Repeat
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VarianceAnnotationsTest {
    private val refs = listOf("doc-1")

    private fun decide(
        cls: String?,
        references: List<String> = refs,
        runAttested: Boolean = false,
        existing: Annotation? = null,
        box: String = "BOX1",
    ) = VarianceAnnotations.decide(runAttested, existing, box, cls, references)

    @Test
    fun `INV-AC-679 a bad debt relief residual annotated OUTSIDE_BY_RULE with one reference is accepted`() {
        decide("OUTSIDE_BY_RULE") shouldBe Accepted(Annotation("BOX1", VarianceClass.OUTSIDE_BY_RULE, refs))
    }

    @Test
    fun `each of the five assignable classes is accepted`() {
        listOf(
            VarianceClass.TIMING_DIFFERENCE,
            VarianceClass.NO_INVOICE_REQUIRED,
            VarianceClass.INPUT_TAX_NOT_CLAIMED,
            VarianceClass.ERROR_CORRECTED_VIA_F5,
            VarianceClass.OUTSIDE_BY_RULE,
        ).forEach { decide(it.name) shouldBe Accepted(Annotation("BOX1", it, refs)) }
    }

    @Test
    fun `a class outside the five is refused with PER-015`() {
        listOf(
            "EXCLUDED_TXN",
            "PRE_OBLIGATION",
            "BUYER_REJECTED",
            "UNEXPLAINED",
            "timing_difference",
            " TIMING_DIFFERENCE",
            "",
            "OUTSIDE_BY_RULE_X",
        ).forEach {
            val decision = decide(it)
            decision shouldBe Refused(AnnotationRefusal.CLASS_NOT_ASSIGNABLE)
            (decision as Refused).reason.code shouldBe "PER-015"
        }
    }

    @Test
    fun `a null class with references is accepted with a null class`() {
        decide(null) shouldBe Accepted(Annotation("BOX1", null, refs))
    }

    @Test
    fun `an attested run gives RUN_ATTESTED before any other rule`() {
        decide("EXCLUDED_TXN", references = emptyList(), runAttested = true) shouldBe
            Refused(AnnotationRefusal.RUN_ATTESTED)
        decide("TIMING_DIFFERENCE", runAttested = true) shouldBe Refused(AnnotationRefusal.RUN_ATTESTED)
    }

    @Test
    fun `empty references with a valid class give NO_REFERENCES before the class rule`() {
        decide("TIMING_DIFFERENCE", references = emptyList()) shouldBe Refused(AnnotationRefusal.NO_REFERENCES)
        decide("UNEXPLAINED", references = emptyList()) shouldBe Refused(AnnotationRefusal.NO_REFERENCES)
    }

    @Test
    fun `the same request twice gives Repeat of the existing annotation`() {
        val first = (decide("TIMING_DIFFERENCE") as Accepted).annotation
        decide("TIMING_DIFFERENCE", existing = first) shouldBe Repeat(first)
    }

    @Test
    fun `an existing annotation differing in class, references or box is not a repeat`() {
        val existing = Annotation("BOX1", VarianceClass.TIMING_DIFFERENCE, refs)
        decide("NO_INVOICE_REQUIRED", existing = existing) shouldBe
            Accepted(Annotation("BOX1", VarianceClass.NO_INVOICE_REQUIRED, refs))
        decide("TIMING_DIFFERENCE", references = listOf("doc-2"), existing = existing) shouldBe
            Accepted(Annotation("BOX1", VarianceClass.TIMING_DIFFERENCE, listOf("doc-2")))
        decide("TIMING_DIFFERENCE", existing = existing, box = "BOX2") shouldBe
            Accepted(Annotation("BOX2", VarianceClass.TIMING_DIFFERENCE, refs))
    }

    @Test
    fun `a repeat with an unassignable class is still refused`() {
        val existing = Annotation("BOX1", null, refs)
        decide("UNEXPLAINED", existing = existing) shouldBe Refused(AnnotationRefusal.CLASS_NOT_ASSIGNABLE)
    }
}
