package com.dynokore.gst.ingestion.domain.completeness

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class ControlTotalsAgreementTest {
    private fun line(
        code: String,
        amount: String,
    ) = TaxCodeTotal(code, BigDecimal(amount))

    private fun totals(
        count: Long,
        vararg lines: TaxCodeTotal,
    ) = PeriodTotals(count, lines.toList())

    private fun compare(
        upstream: PeriodTotals,
        ingested: PeriodTotals,
    ) = ControlTotalsAgreement.compare(upstream, ingested)

    @Test
    fun `INV-AC-1847 count 4200 against 4199 with equal totals raises PER-002 on the count`() {
        val verdict = compare(totals(4200, line("SR", "91560.00")), totals(4199, line("SR", "91560.00")))
        verdict shouldBe ControlTotalsVerdict.Disagrees("PER-002", CountDifference(4200, 4199), emptyList())
    }

    @Test
    fun `INV-AC-1847 equal count with SR 91560_00 against 91559_99 raises PER-002 on the value`() {
        val verdict = compare(totals(4200, line("SR", "91560.00")), totals(4200, line("SR", "91559.99")))
        verdict shouldBe
            ControlTotalsVerdict.Disagrees(
                "PER-002",
                null,
                listOf(TaxCodeDifference("SR", BigDecimal("91560.00"), BigDecimal("91559.99"))),
            )
    }

    @Test
    fun `INV-AC-1847 count and value both different carries both`() {
        val verdict = compare(totals(4200, line("SR", "91560.00")), totals(4199, line("SR", "91559.99")))
        verdict shouldBe
            ControlTotalsVerdict.Disagrees(
                "PER-002",
                CountDifference(4200, 4199),
                listOf(TaxCodeDifference("SR", BigDecimal("91560.00"), BigDecimal("91559.99"))),
            )
    }

    @Test
    fun `INV-AC-1847 count 4200 and SR 91560_00 on both sides agrees`() {
        compare(totals(4200, line("SR", "91560.00")), totals(4200, line("SR", "91560.00"))) shouldBe
            ControlTotalsVerdict.Agrees
    }

    @Test
    fun `scale does not matter, 100_0 agrees with 100_00`() {
        compare(totals(1, line("SR", "100.0")), totals(1, line("SR", "100.00"))) shouldBe ControlTotalsVerdict.Agrees
    }

    @Test
    fun `codes on one side only are differences against zero in ascending code order`() {
        val verdict = compare(totals(2, line("ZR", "5.00")), totals(2, line("TX", "7.00")))
        verdict shouldBe
            ControlTotalsVerdict.Disagrees(
                "PER-002",
                null,
                listOf(
                    TaxCodeDifference("TX", BigDecimal.ZERO, BigDecimal("7.00")),
                    TaxCodeDifference("ZR", BigDecimal("5.00"), BigDecimal.ZERO),
                ),
            )
    }

    @Test
    fun `an upstream zero line for a code the ingested side lacks agrees`() {
        compare(totals(1, line("ZR", "0.00")), totals(1)) shouldBe ControlTotalsVerdict.Agrees
    }

    @Test
    fun `tax codes are case-sensitive`() {
        val verdict = compare(totals(1, line("sr", "1.00")), totals(1, line("SR", "1.00")))
        verdict shouldBe
            ControlTotalsVerdict.Disagrees(
                "PER-002",
                null,
                listOf(
                    TaxCodeDifference("SR", BigDecimal.ZERO, BigDecimal("1.00")),
                    TaxCodeDifference("sr", BigDecimal("1.00"), BigDecimal.ZERO),
                ),
            )
    }

    @Test
    fun `two empty lists with count 0 agree`() {
        compare(totals(0), totals(0)) shouldBe ControlTotalsVerdict.Agrees
    }

    @Test
    fun `a negative count is rejected on either side`() {
        assertThrows<IllegalArgumentException> { compare(totals(-1), totals(0)) }
        assertThrows<IllegalArgumentException> { compare(totals(0), totals(-1)) }
    }

    @Test
    fun `a blank code is rejected`() {
        assertThrows<IllegalArgumentException> { compare(totals(0, line("", "1.00")), totals(0)) }
        assertThrows<IllegalArgumentException> { compare(totals(0), totals(0, line("  ", "1.00"))) }
    }

    @Test
    fun `a repeated code on one side is rejected`() {
        assertThrows<IllegalArgumentException> {
            compare(totals(0, line("SR", "1.00"), line("SR", "2.00")), totals(0))
        }
        assertThrows<IllegalArgumentException> {
            compare(totals(0), totals(0, line("SR", "1.00"), line("SR", "1.00")))
        }
    }

    @Test
    fun `PER_002 is PER-002`() {
        ControlTotalsAgreement.PER_002 shouldBe "PER-002"
    }
}
