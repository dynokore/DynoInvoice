package com.dynokore.gst.canonical.application.document

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TaxSubtotalsTest {
    private fun line(
        n: Int,
        code: String,
        rate: String,
        net: String,
    ) = TaxedLine(n, code, rate, net)

    private fun derived(vararg lines: TaxedLine): List<List<String>> =
        (TaxSubtotals.derive(lines.toList()) as SubtotalResult.Derived).subtotals.map {
            listOf(it.taxCode, it.taxableAmount, it.taxAmount)
        }

    @Test
    fun `INV-AC-21599 T1 two lines of one category and rate give one subtotal`() {
        derived(line(1, "SR", "9.00", "100.00"), line(2, "SR", "9.00", "200.00")) shouldBe
            listOf(listOf("SR", "300.00", "27.00"))
    }

    @Test
    fun `INV-AC-21599 T2 tax is rounded half-up to two decimals`() {
        derived(line(1, "SR", "9.00", "0.50")) shouldBe listOf(listOf("SR", "0.50", "0.05"))
        derived(line(1, "SR", "9.00", "0.05")) shouldBe listOf(listOf("SR", "0.05", "0.00"))
        derived(line(1, "SR", "9.00", "-0.50")) shouldBe listOf(listOf("SR", "-0.50", "-0.05"))
    }

    @Test
    fun `INV-AC-21599 T3 subtotals keep the order of first appearance`() {
        derived(
            line(1, "SR", "9.00", "100.00"),
            line(2, "ZR", "0.00", "50.00"),
            line(3, "SR", "9.00", "10.00"),
        ) shouldBe
            listOf(listOf("SR", "110.00", "9.90"), listOf("ZR", "50.00", "0.00"))
        derived(line(1, "ZR", "0.00", "50.00"), line(2, "SR", "9.00", "10.00")) shouldBe
            listOf(listOf("ZR", "50.00", "0.00"), listOf("SR", "10.00", "0.90"))
    }

    @Test
    fun `INV-AC-21599 T4 the rate is part of the grouping key`() {
        derived(line(1, "SR", "8.00", "100.00"), line(2, "SR", "9.00", "100.00")) shouldBe
            listOf(listOf("SR", "100.00", "8.00"), listOf("SR", "100.00", "9.00"))
    }

    @Test
    fun `INV-AC-21599 T5 tax is computed on the sum, not per line`() {
        derived(line(1, "SR", "9.00", "0.05"), line(2, "SR", "9.00", "0.05")) shouldBe
            listOf(listOf("SR", "0.10", "0.01"))
    }

    @Test
    fun `INV-AC-21599 T6 amounts carry exactly two decimals`() {
        derived(line(1, "SR", "9.00", "100"), line(2, "SR", "9.00", "200.5")) shouldBe
            listOf(listOf("SR", "300.50", "27.05"))
    }

    @Test
    fun `INV-AC-21599 T7 a blank category is not derived and no partial list is returned`() {
        val r = TaxSubtotals.derive(listOf(line(1, "SR", "9.00", "10.00"), line(2, " ", "9.00", "5.00")))
        r shouldBe SubtotalResult.NotDerived(listOf(2))
        (r is SubtotalResult.Derived) shouldBe false
    }

    @Test
    fun `INV-AC-21599 T4 same rate written differently is one group`() {
        derived(line(1, "SR", "9", "10.00"), line(2, "SR", "9.00", "10.00")) shouldBe
            listOf(listOf("SR", "20.00", "1.80"))
    }
}
