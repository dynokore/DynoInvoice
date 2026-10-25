package com.dynokore.gst.reconciliation.domain.variance

import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.BUYER_REJECTED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.ERROR_CORRECTED_VIA_F5
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.EXCLUDED_TXN
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.INPUT_TAX_NOT_CLAIMED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.NO_INVOICE_REQUIRED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.OUTSIDE_BY_RULE
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.PRE_OBLIGATION
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.TIMING_DIFFERENCE
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.UNEXPLAINED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VarianceClassesTest {
    private fun derive(
        verdict: String?,
        reason: String? = null,
    ) = VarianceClasses.derive(DocumentFacts(verdict, reason))

    @Test
    fun `the vocabulary has the nine members in order and each value equals its name`() {
        VarianceClass.entries.map { it.name } shouldBe
            listOf(
                "TIMING_DIFFERENCE",
                "NO_INVOICE_REQUIRED",
                "INPUT_TAX_NOT_CLAIMED",
                "ERROR_CORRECTED_VIA_F5",
                "EXCLUDED_TXN",
                "PRE_OBLIGATION",
                "OUTSIDE_BY_RULE",
                "BUYER_REJECTED",
                "UNEXPLAINED",
            )
        VarianceClass.entries.all { it.value == it.name } shouldBe true
    }

    @Test
    fun `INV-AC-678 January and February documents of an entity starting 1 March are PRE_OBLIGATION`() {
        val january = DocumentFacts("PRE_OBLIGATION", null)
        val february = DocumentFacts("PRE_OBLIGATION", null)
        VarianceClasses.derive(january) shouldBe PRE_OBLIGATION
        VarianceClasses.derive(february) shouldBe PRE_OBLIGATION
    }

    @Test
    fun `INV-AC-678 a SUBMITTABLE document derives null so its amount is not classified`() {
        derive("SUBMITTABLE") shouldBe null
    }

    @Test
    fun `INV-AC-10268 four classes are derived, an unplaceable version gets none`() {
        listOf(
            derive("EXCLUDED_TXN"),
            derive("PRE_OBLIGATION"),
            derive("OUT_OF_SCOPE"),
            derive("NOT_SUBMITTABLE", "BUYER_REJECTED"),
            derive("NOT_SUBMITTABLE"),
            derive("NOT_SUBMITTABLE", "ISSUANCE_REFUSED"),
        ) shouldBe listOf(EXCLUDED_TXN, PRE_OBLIGATION, OUTSIDE_BY_RULE, BUYER_REJECTED, null, null)
    }

    @Test
    fun `INV-AC-10268 UNEXPLAINED and a look-alike are not assignable`() {
        VarianceClasses.parseAssignable("UNEXPLAINED") shouldBe null
        VarianceClasses.parseAssignable("timing_difference") shouldBe null
    }

    @Test
    fun `derive EXCLUDED_TXN`() = derive("EXCLUDED_TXN") shouldBe EXCLUDED_TXN

    @Test
    fun `derive PRE_OBLIGATION`() = derive("PRE_OBLIGATION") shouldBe PRE_OBLIGATION

    @Test
    fun `derive OUT_OF_SCOPE gives OUTSIDE_BY_RULE`() = derive("OUT_OF_SCOPE") shouldBe OUTSIDE_BY_RULE

    @Test
    fun `derive NOT_SUBMITTABLE with BUYER_REJECTED`() =
        derive("NOT_SUBMITTABLE", "BUYER_REJECTED") shouldBe BUYER_REJECTED

    @Test
    fun `derive NOT_SUBMITTABLE with no reason is null`() = derive("NOT_SUBMITTABLE") shouldBe null

    @Test
    fun `derive NOT_SUBMITTABLE with ISSUANCE_REFUSED is null`() =
        derive("NOT_SUBMITTABLE", "ISSUANCE_REFUSED") shouldBe null

    @Test
    fun `derive NOT_SUBMITTABLE with NETTED_INTO_REVISION is null`() =
        derive("NOT_SUBMITTABLE", "NETTED_INTO_REVISION") shouldBe null

    @Test
    fun `derive NOT_SUBMITTABLE with an unknown or padded or lower-case reason is null`() {
        derive("NOT_SUBMITTABLE", "SOMETHING_ELSE") shouldBe null
        derive("NOT_SUBMITTABLE", "buyer_rejected") shouldBe null
        derive("NOT_SUBMITTABLE", " BUYER_REJECTED") shouldBe null
        derive("NOT_SUBMITTABLE", "") shouldBe null
    }

    @Test
    fun `derive SUBMITTABLE and IN_SCOPE are null`() {
        derive("SUBMITTABLE") shouldBe null
        derive("IN_SCOPE") shouldBe null
    }

    @Test
    fun `derive a null verdict or an unknown verdict is null`() {
        derive(null) shouldBe null
        derive(null, "BUYER_REJECTED") shouldBe null
        derive("SOMETHING_ELSE") shouldBe null
    }

    @Test
    fun `derive compares case-sensitively and untrimmed`() {
        derive("excluded_txn") shouldBe null
        derive("Out_Of_Scope") shouldBe null
        derive(" EXCLUDED_TXN") shouldBe null
        derive("PRE_OBLIGATION ") shouldBe null
        derive("EXCLUDED_TXN\n") shouldBe null
        derive("not_submittable", "BUYER_REJECTED") shouldBe null
    }

    @Test
    fun `derive look-alike EXCLUDED_TXN_X is null`() {
        derive("EXCLUDED_TXN_X") shouldBe null
        derive("XOUT_OF_SCOPE") shouldBe null
    }

    @Test
    fun `derive never returns UNEXPLAINED or an assignable-only class`() {
        val verdicts =
            listOf(
                null,
                "",
                "SUBMITTABLE",
                "NOT_SUBMITTABLE",
                "PRE_OBLIGATION",
                "IN_SCOPE",
                "OUT_OF_SCOPE",
                "EXCLUDED_TXN",
            )
        val reasons = listOf(null, "", "BUYER_REJECTED", "ISSUANCE_REFUSED", "NETTED_INTO_REVISION", "UNEXPLAINED")
        val results = verdicts.flatMap { v -> reasons.map { r -> derive(v, r) } }.filterNotNull().toSet()
        (results - VarianceClasses.derived) shouldBe emptySet()
        results.contains(UNEXPLAINED) shouldBe false
    }

    @Test
    fun `parseAssignable accepts each of the five`() {
        listOf(TIMING_DIFFERENCE, NO_INVOICE_REQUIRED, INPUT_TAX_NOT_CLAIMED, ERROR_CORRECTED_VIA_F5, OUTSIDE_BY_RULE)
            .forEach { VarianceClasses.parseAssignable(it.name) shouldBe it }
    }

    @Test
    fun `parseAssignable refuses derived classes, UNEXPLAINED, look-alikes, empty, null`() {
        listOf(
            "EXCLUDED_TXN",
            "PRE_OBLIGATION",
            "BUYER_REJECTED",
            "UNEXPLAINED",
            "timing_difference",
            " TIMING_DIFFERENCE",
            "TIMING_DIFFERENCE ",
            "TIMING_DIFFERENCE,NO_INVOICE_REQUIRED",
            "*",
            "",
        ).forEach { VarianceClasses.parseAssignable(it) shouldBe null }
        VarianceClasses.parseAssignable(null) shouldBe null
    }

    @Test
    fun `the derived and assignable sets are exactly as stated and their union is the vocabulary`() {
        VarianceClasses.derived shouldBe setOf(EXCLUDED_TXN, PRE_OBLIGATION, OUTSIDE_BY_RULE, BUYER_REJECTED)
        VarianceClasses.assignable shouldBe
            setOf(
                TIMING_DIFFERENCE,
                NO_INVOICE_REQUIRED,
                INPUT_TAX_NOT_CLAIMED,
                ERROR_CORRECTED_VIA_F5,
                OUTSIDE_BY_RULE,
            )
        (VarianceClasses.derived + VarianceClasses.assignable) shouldBe (VarianceClass.entries.toSet() - UNEXPLAINED)
    }
}
