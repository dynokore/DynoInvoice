package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RenditionWordingCheckerTest {
    private fun req(vararg texts: String) = texts.map { WordingRequirement(it, "cite") }

    private val taxInvoice = req("Tax Invoice")
    private val selfBilling = "Buyer Created Tax Invoice – Approved by Comptroller of GST"

    @Test
    fun `INV-AC-681 a Type 1A sample lacking Tax Invoice passes and a Type 2 sample fails`() {
        val text = "Invoice no 1"
        RenditionWordingChecker.check(taxInvoice, SubmissionType.TYPE_1A, text).result shouldBe SampleResult.PASSED
        val type2 = RenditionWordingChecker.check(taxInvoice, SubmissionType.TYPE_2, text)
        type2.result shouldBe SampleResult.FAILED
        type2.missingWording shouldBe listOf("Tax Invoice")
    }

    @Test
    fun `INV-AC-1858 a Type 3 sample lacking the self-billing wording fails`() {
        val r = RenditionWordingChecker.check(req(selfBilling), SubmissionType.TYPE_3, "Invoice no 1")
        r.result shouldBe SampleResult.FAILED
        r.missingWording shouldBe listOf(selfBilling)
    }

    @Test
    fun `INV-AC-674 a failed Type 3 sample opens the entity standing with changed true`() {
        val r = RenditionWordingChecker.check(req(selfBilling), SubmissionType.TYPE_3, "Invoice no 1")
        val s = EntityRenditionStanding.record(emptyMap(), "S1", SubmissionType.TYPE_3, r.result)
        s.open shouldBe true
        s.changed shouldBe true
    }

    @Test
    fun `Tax Invoice exemption for 1A and 1B while other wording is still checked`() {
        val text = "Invoice no 1"
        for (t in listOf(SubmissionType.TYPE_1A, SubmissionType.TYPE_1B)) {
            RenditionWordingChecker.check(taxInvoice, t, text).result shouldBe SampleResult.PASSED
            val r = RenditionWordingChecker.check(req("Tax Invoice", "Self-billing"), t, text)
            r.result shouldBe SampleResult.FAILED
            r.missingWording shouldBe listOf("Self-billing")
            RenditionWordingChecker.check(req("Tax Invoice Copy"), t, text).result shouldBe SampleResult.FAILED
        }
        RenditionWordingChecker.check(taxInvoice, SubmissionType.TYPE_2, text).missingWording shouldBe
            listOf("Tax Invoice")
    }

    @Test
    fun `a no-break space between wordings is normalised`() {
        val reqs = req("Wording One", "Wording Two")
        val first = RenditionWordingChecker.check(reqs, SubmissionType.TYPE_2, "Wording One")
        first.result shouldBe SampleResult.FAILED
        first.missingWording shouldBe listOf("Wording Two")
        first.textFound shouldBe true
        RenditionWordingChecker.check(reqs, SubmissionType.TYPE_2, "Wording One\u00A0Wording Two").result shouldBe
            SampleResult.PASSED
    }

    @Test
    fun `the standing follows the latest result of every pair`() {
        val failed = RenditionWordingChecker.check(req(selfBilling), SubmissionType.TYPE_3, "x")
        failed.result shouldBe SampleResult.FAILED
        failed.missingWording shouldBe listOf(selfBilling)
        val a1 = EntityRenditionStanding.record(emptyMap(), "S1", SubmissionType.TYPE_3, SampleResult.FAILED)
        (a1.open to a1.changed) shouldBe (true to true)
        val b = EntityRenditionStanding.record(a1.latest, "S2", SubmissionType.TYPE_2, SampleResult.PASSED)
        (b.open to b.changed) shouldBe (true to false)
        val a2 = EntityRenditionStanding.record(b.latest, "S1", SubmissionType.TYPE_3, SampleResult.PASSED)
        (a2.open to a2.changed) shouldBe (false to true)
        val a3 = EntityRenditionStanding.record(a2.latest, "S1", SubmissionType.TYPE_3, SampleResult.PASSED)
        (a3.open to a3.changed) shouldBe (false to false)
    }

    @Test
    fun `whitespace and no-break space inside the wording match`() {
        val r = RenditionWordingChecker.check(req("Tax  Invoice"), SubmissionType.TYPE_2, "A Tax\u00A0\n Invoice B")
        r.result shouldBe SampleResult.PASSED
    }

    @Test
    fun `a differing case gives FAILED`() {
        RenditionWordingChecker.check(taxInvoice, SubmissionType.TYPE_2, "tax invoice").result shouldBe
            SampleResult.FAILED
    }

    @Test
    fun `a hyphen for the en dash gives FAILED`() {
        val text = "Buyer Created Tax Invoice - Approved by Comptroller of GST"
        RenditionWordingChecker.check(req(selfBilling), SubmissionType.TYPE_3, text).result shouldBe SampleResult.FAILED
    }

    @Test
    fun `the look-alike Tax Invoice Copy stays required for Type 1A`() {
        RenditionWordingChecker.check(req("Tax Invoice Copy"), SubmissionType.TYPE_1A, "Tax Invoice").result shouldBe
            SampleResult.FAILED
    }

    @Test
    fun `null text with a requirement gives FAILED and textFound false`() {
        val r = RenditionWordingChecker.check(taxInvoice, SubmissionType.TYPE_2, null)
        r.result shouldBe SampleResult.FAILED
        r.textFound shouldBe false
    }

    @Test
    fun `null text with no requirement gives PASSED`() {
        val r = RenditionWordingChecker.check(emptyList(), SubmissionType.TYPE_2, null)
        r.result shouldBe SampleResult.PASSED
        r.textFound shouldBe false
    }

    @Test
    fun `the standing with pair A failed and pair B passed is open then closes then repeats unchanged`() {
        val a = EntityRenditionStanding.record(emptyMap(), "A", SubmissionType.TYPE_2, SampleResult.FAILED)
        val b = EntityRenditionStanding.record(a.latest, "B", SubmissionType.TYPE_2, SampleResult.PASSED)
        b.open shouldBe true
        val closed = EntityRenditionStanding.record(b.latest, "A", SubmissionType.TYPE_2, SampleResult.PASSED)
        (closed.open to closed.changed) shouldBe (false to true)
        EntityRenditionStanding.record(closed.latest, "A", SubmissionType.TYPE_2, SampleResult.PASSED).changed shouldBe
            false
    }
}
