package com.dynokore.gst.aggregation.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AggregateLinesTest {
    private fun line(
        code: String,
        amount: String,
    ) = AggregateLine(code, amount)

    @Test
    fun `merge adds the amounts of one code and keeps the order of first appearance`() {
        AggregateLines.merge(listOf(line("B", "1.50"), line("A", "2"), line("B", "0.25"))) shouldBe
            listOf(line("B", "1.75"), line("A", "2"))
    }

    @Test
    fun `INV-REQ-434 the difference is per code, leaves out a zero and negates a code only in the transmitted lines`() {
        AggregateLines.difference(
            revision = listOf(line("SR", "1600.00"), line("ZR", "5.00")),
            transmitted = listOf(line("SR", "1500.00"), line("ZR", "5.00"), line("ES", "3.00")),
        ) shouldBe listOf(line("SR", "100.00"), line("ES", "-3.00"))
    }

    private fun pcp(
        code: String,
        amount: String,
        taxable: String?,
    ) = AggregateLine(code, amount, taxable)

    private val five =
        listOf(
            pcp("S1", "9.00", "100.00"),
            pcp("S1", "4.00", "50.00"),
            pcp("Z1", "0.00", "20.00"),
            pcp("X1", "1.00", "10.00"),
            pcp("X1", "2.00", null),
        )
    private val sent = listOf(pcp("S1", "9.00", "100.00"), pcp("Z1", "0.00", "20.00"), pcp("X1", "3.00", "30.00"))

    @Test
    fun `INV-AC-23680 merge states taxableAmount only when every line of the code states it`() {
        AggregateLines.merge(five) shouldBe
            listOf(pcp("S1", "13.00", "150.00"), pcp("Z1", "0.00", "20.00"), pcp("X1", "3.00", null))
    }

    @Test
    fun `INV-AC-23680 difference states the taxable difference and drops a code unchanged in both`() {
        val revision = listOf(pcp("S1", "13.00", "150.00"), pcp("Z1", "0.00", "20.00"), pcp("X1", "3.00", null))
        AggregateLines.difference(revision, sent) shouldBe listOf(pcp("S1", "4.00", "50.00"))
    }

    @Test
    fun `INV-AC-23680 difference keeps a code whose amount is unchanged and taxable amount changed`() {
        AggregateLines.difference(listOf(pcp("S1", "13.00", "155.00")), listOf(pcp("S1", "13.00", "150.00"))) shouldBe
            listOf(pcp("S1", "0.00", "5.00"))
    }

    @Test
    fun `INV-AC-23680 difference states no taxable amount when the transmitted line has none`() {
        AggregateLines.difference(listOf(pcp("S1", "13.00", "150.00")), listOf(pcp("S1", "9.00", null))) shouldBe
            listOf(pcp("S1", "4.00", null))
    }

    @Test
    fun `INV-AC-23680 POS lines state no taxable amount`() {
        AggregateLines.merge(listOf(line("S1", "10.00"), line("S1", "6.00"))) shouldBe listOf(line("S1", "16.00"))
        AggregateLines.difference(listOf(line("S1", "10.00")), listOf(line("S1", "6.00"))) shouldBe
            listOf(line("S1", "4.00"))
    }
}
