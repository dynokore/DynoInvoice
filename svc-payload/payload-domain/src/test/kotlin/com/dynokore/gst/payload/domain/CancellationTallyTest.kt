package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CancellationTallyTest {
    private fun d(s: String) = BigDecimal(s)

    private fun basis(
        currency: String = "SGD",
        groups: List<TallyGroup> = listOf(TallyGroup("SR", d("100.00"), d("9.00"))),
        totals: DocumentTotals = DocumentTotals(d("100.00"), d("9.00"), d("109.00")),
    ) = TallyBasis(currency, groups, totals)

    private fun kinds(r: TallyResult) = (r as TallyResult.Mismatches).list

    @Test
    fun `INV-AC-1744 identical bases give Tallies`() {
        CancellationTally.compare(basis(), basis()) shouldBe TallyResult.Tallies
    }

    @Test
    fun `INV-AC-1744 100_00 against 100_0 gives Tallies`() {
        val b =
            basis(
                groups = listOf(TallyGroup("SR", d("100.0"), d("9.0"))),
                totals = DocumentTotals(d("100.0"), d("9.0"), d("109.0")),
            )
        CancellationTally.compare(basis(), b) shouldBe TallyResult.Tallies
    }

    @Test
    fun `INV-AC-10441 a credit note with negated amounts and scale 1 gives Tallies`() {
        val a =
            basis(
                groups = listOf(TallyGroup("SR", d("-100.0"), d("-9.0"))),
                totals = DocumentTotals(d("-100.0"), d("-9.0"), d("-109.0")),
            )
        CancellationTally.compare(basis(), a) shouldBe TallyResult.Tallies
    }

    @Test
    fun `INV-AC-601 taxable 100_00 against 100_01 gives one TAXABLE_AMOUNT for SR`() {
        val b = basis(groups = listOf(TallyGroup("SR", d("100.01"), d("9.00"))))
        kinds(CancellationTally.compare(basis(), b)) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.TAXABLE_AMOUNT, "SR", "100.00", "100.01"))
    }

    @Test
    fun `INV-AC-1744 tax 9_00 against 8_99 gives one TAX_AMOUNT`() {
        val b = basis(groups = listOf(TallyGroup("SR", d("100.00"), d("8.99"))))
        kinds(CancellationTally.compare(basis(), b)) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.TAX_AMOUNT, "SR", "9.00", "8.99"))
    }

    @Test
    fun `INV-AC-10441 currency SGD against USD gives CURRENCY`() {
        kinds(CancellationTally.compare(basis(), basis(currency = "USD"))) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.CURRENCY, null, "SGD", "USD"))
    }

    @Test
    fun `INV-AC-601 an extra code on the credit note and a missing code each give CATEGORY_CODE_SET`() {
        val sr = TallyGroup("SR", d("100.00"), d("9.00"))
        val zr = TallyGroup("ZR", d("50.00"), d("0.00"))
        kinds(CancellationTally.compare(basis(), basis(groups = listOf(sr, zr)))) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.CATEGORY_CODE_SET, "ZR", null, "50.00"))
        kinds(CancellationTally.compare(basis(groups = listOf(sr, zr)), basis())) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.CATEGORY_CODE_SET, "ZR", "50.00", null))
    }

    @Test
    fun `INV-AC-10441 an extra code with two groups carries their taxable sum`() {
        val extra = listOf(TallyGroup("ZR", d("20.00"), d("1.00")), TallyGroup("ZR", d("30.00"), d("2.00")))
        val sr = TallyGroup("SR", d("100.00"), d("9.00"))
        kinds(CancellationTally.compare(basis(), basis(groups = listOf(sr) + extra))) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.CATEGORY_CODE_SET, "ZR", null, "50.00"))
    }

    @Test
    fun `INV-AC-10441 an extra code with taxable zero carries the zero string`() {
        val sr = TallyGroup("SR", d("100.00"), d("9.00"))
        val zr = TallyGroup("ZR", d("0.00"), d("0.00"))
        kinds(CancellationTally.compare(basis(), basis(groups = listOf(sr, zr)))) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.CATEGORY_CODE_SET, "ZR", null, "0.00"))
    }

    @Test
    fun `INV-AC-601 code sr against SR gives two CATEGORY_CODE_SET mismatches`() {
        val b = basis(groups = listOf(TallyGroup("sr", d("100.00"), d("9.00"))))
        kinds(CancellationTally.compare(basis(), b)).map { it.kind to it.key } shouldBe
            listOf(
                TallyMismatchKind.CATEGORY_CODE_SET to "SR",
                TallyMismatchKind.CATEGORY_CODE_SET to "sr",
            )
    }

    @Test
    fun `INV-AC-601 code SRCA-S against SR gives two CATEGORY_CODE_SET mismatches`() {
        val b = basis(groups = listOf(TallyGroup("SRCA-S", d("100.00"), d("9.00"))))
        kinds(CancellationTally.compare(basis(), b)).map { it.kind to it.key } shouldBe
            listOf(
                TallyMismatchKind.CATEGORY_CODE_SET to "SR",
                TallyMismatchKind.CATEGORY_CODE_SET to "SRCA-S",
            )
    }

    @Test
    fun `INV-AC-1744 two groups of one code summed equal one group on the other side`() {
        val b = basis(groups = listOf(TallyGroup("SR", d("60.00"), d("5.40")), TallyGroup("SR", d("40.00"), d("3.60"))))
        CancellationTally.compare(basis(), b) shouldBe TallyResult.Tallies
    }

    @Test
    fun `INV-AC-10441 a differing gross total alone gives one DOCUMENT_TOTAL`() {
        val b = basis(totals = DocumentTotals(d("100.00"), d("9.00"), d("109.01")))
        kinds(CancellationTally.compare(basis(), b)) shouldBe
            listOf(TallyMismatch(TallyMismatchKind.DOCUMENT_TOTAL, "grossTotal", "109.00", "109.01"))
    }

    @Test
    fun `INV-AC-10441 several differences give all mismatches in the stated order`() {
        val o =
            basis(
                groups =
                    listOf(
                        TallyGroup("ZR", d("1"), d("0")),
                        TallyGroup("SR", d("100"), d("9")),
                        TallyGroup("ES", d("2"), d("0")),
                    ),
            )
        val c =
            TallyBasis(
                "USD",
                listOf(
                    TallyGroup("ZR", d("2"), d("1")),
                    TallyGroup("SR", d("101"), d("8")),
                    TallyGroup("AA", d("3"), d("0")),
                ),
                DocumentTotals(d("1"), d("2"), d("3")),
            )
        kinds(CancellationTally.compare(o, c)).map { it.kind to it.key } shouldBe
            listOf(
                TallyMismatchKind.CURRENCY to null,
                TallyMismatchKind.CATEGORY_CODE_SET to "AA",
                TallyMismatchKind.CATEGORY_CODE_SET to "ES",
                TallyMismatchKind.TAXABLE_AMOUNT to "SR",
                TallyMismatchKind.TAX_AMOUNT to "SR",
                TallyMismatchKind.TAXABLE_AMOUNT to "ZR",
                TallyMismatchKind.TAX_AMOUNT to "ZR",
                TallyMismatchKind.DOCUMENT_TOTAL to "netTotal",
                TallyMismatchKind.DOCUMENT_TOTAL to "taxTotal",
                TallyMismatchKind.DOCUMENT_TOTAL to "grossTotal",
            )
    }

    @Test
    fun `INV-AC-10441 an unknown original gives exactly ORIGINAL_UNKNOWN`() {
        CancellationTally.compare(null, basis()) shouldBe
            TallyResult.Mismatches(listOf(TallyMismatch(TallyMismatchKind.ORIGINAL_UNKNOWN, null, null, null)))
    }

    @Test
    fun `INV-AC-10441 the mismatch kind enum has exactly the six constants`() {
        TallyMismatchKind.entries.map { it.name } shouldBe
            listOf(
                "CURRENCY",
                "CATEGORY_CODE_SET",
                "TAXABLE_AMOUNT",
                "TAX_AMOUNT",
                "DOCUMENT_TOTAL",
                "ORIGINAL_UNKNOWN",
            )
    }
}
