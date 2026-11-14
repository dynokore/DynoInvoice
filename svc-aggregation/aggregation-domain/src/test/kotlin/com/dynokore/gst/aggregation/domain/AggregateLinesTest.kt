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
}
