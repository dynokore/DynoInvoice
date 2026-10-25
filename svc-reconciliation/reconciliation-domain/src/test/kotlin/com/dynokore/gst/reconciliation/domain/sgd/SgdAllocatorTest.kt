package com.dynokore.gst.reconciliation.domain.sgd

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SgdAllocatorTest {
    private fun d(s: String) = BigDecimal(s)

    private fun line(
        n: Int,
        amount: String,
        tax: String = "0.00",
        code: String = "SR",
    ) = AllocationLine(n, d(amount), d(tax), code)

    private fun stated(
        excl: String,
        tax: String,
        rate: String,
    ) = StatedSgd(d(excl), d(tax), d(rate))

    private fun allocated(a: SgdAllocation) = (a as SgdAllocation.Allocated).lines

    @Test
    fun `the difference of 0_01 goes to line 2 the larger so the sum is exactly the stated total`() {
        val r =
            allocated(
                SgdAllocator.allocate(
                    "USD",
                    listOf(line(1, "100.01"), line(2, "200.02")),
                    stated("405.05", "0.00", "1.35"),
                ),
            )
        r.map { it.taxableAmountSgd } shouldBe listOf(d("135.01"), d("270.04"))
        r.fold(BigDecimal.ZERO) { s, l -> s + l.taxableAmountSgd } shouldBe d("405.05")
    }

    @Test
    fun `a tie of two equal lines gives the difference to the lower lineNumber`() {
        val r =
            allocated(
                SgdAllocator.allocate("USD", listOf(line(2, "10.00"), line(1, "10.00")), stated("20.01", "0.00", "1")),
            )
        r.map { it.taxableAmountSgd } shouldBe listOf(d("10.01"), d("10.00"))
    }

    @Test
    fun `a negative difference is subtracted from the largest line`() {
        val r =
            allocated(
                SgdAllocator.allocate("USD", listOf(line(1, "1.00"), line(2, "5.00")), stated("5.99", "0.00", "1")),
            )
        r.map { it.taxableAmountSgd } shouldBe listOf(d("1.00"), d("4.99"))
    }

    @Test
    fun `a negative line credit rounds HALF_UP away from zero at 005`() {
        val r = allocated(SgdAllocator.allocate("USD", listOf(line(1, "-0.005")), stated("-0.01", "0.00", "1")))
        r.single().taxableAmountSgd shouldBe d("-0.01")
        // HALF_EVEN would round line 1 to 0.00 and push the -0.01 onto line 2
        val two =
            allocated(
                SgdAllocator.allocate("USD", listOf(line(1, "-0.005"), line(2, "-9.00")), stated("-9.01", "0.00", "1")),
            )
        two.map { it.taxableAmountSgd } shouldBe listOf(d("-0.01"), d("-9.00"))
    }

    @Test
    fun `a shuffled line order gives the same list`() {
        val ls = listOf(line(1, "100.01", "1.01"), line(2, "200.02", "2.02"), line(3, "50.00", "0.50"))
        val s = stated("500.00", "6.00", "1.35")
        val expected = SgdAllocator.allocate("USD", ls, s)
        SgdAllocator.allocate("USD", ls.reversed(), s) shouldBe expected
        SgdAllocator.allocate("USD", listOf(ls[1], ls[2], ls[0]), s) shouldBe expected
        allocated(expected).map { it.lineNumber } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `SGD with stated null returns the lines unchanged`() {
        val r = allocated(SgdAllocator.allocate("SGD", listOf(line(1, "10.10", "0.90", "SR")), null))
        r shouldBe listOf(SgdLine(1, "SR", d("10.10"), d("0.90")))
    }

    @Test
    fun `USD with no lines is NO_LINES`() {
        SgdAllocator.allocate("USD", emptyList(), stated("1.00", "0.00", "1")) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES)
    }

    @Test
    fun `USD with stated null is MISSING_SGD_TOTAL`() {
        SgdAllocator.allocate("USD", listOf(line(1, "1.00")), null) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)
    }

    @Test
    fun `USD with rate 0 and -1 is INVALID_EXCHANGE_RATE`() {
        listOf("0", "-1").forEach {
            SgdAllocator.allocate("USD", listOf(line(1, "1.00")), stated("1.00", "0.00", it)) shouldBe
                SgdAllocation.NotReconcilable(NotReconcilableReason.INVALID_EXCHANGE_RATE)
        }
    }

    @Test
    fun `USD with all three faults is NO_LINES and the check order is lines then total then rate`() {
        SgdAllocator.allocate("USD", emptyList(), null) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES)
        SgdAllocator.allocate("USD", emptyList(), stated("1.00", "0.00", "0")) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES)
        SgdAllocator.allocate("USD", listOf(line(1, "1.00")), null) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)
    }

    @Test
    fun `a lower-case sgd is a foreign currency look-alike and is allocated`() {
        SgdAllocator.allocate("sgd", listOf(line(1, "1.00")), null) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)
        allocated(
            SgdAllocator.allocate("sgd", listOf(line(1, "1.00")), stated("2.00", "0.00", "1")),
        ).single().taxableAmountSgd shouldBe
            d("2.00")
    }

    @Test
    fun `the NotReconcilableReason has exactly the three members`() {
        NotReconcilableReason.entries.map { it.name } shouldBe
            listOf("NO_LINES", "MISSING_SGD_TOTAL", "INVALID_EXCHANGE_RATE")
    }

    @Test
    fun `INV-AC-10269 document A allocates 130_01 and 43_33 and tax 11_71 and 3_90 and B with rate 0 is refused`() {
        val ls = listOf(line(1, "100.00", "9.00"), line(2, "33.33", "3.00"))
        val a = allocated(SgdAllocator.allocate("USD", ls, stated("173.34", "15.61", "1.3")))
        a.map { it.taxableAmountSgd } shouldBe listOf(d("130.01"), d("43.33"))
        a.map { it.taxAmountSgd } shouldBe listOf(d("11.71"), d("3.90"))
        SgdAllocator.allocate("USD", ls, stated("173.34", "15.61", "0")) shouldBe
            SgdAllocation.NotReconcilable(NotReconcilableReason.INVALID_EXCHANGE_RATE)
    }

    @Test
    fun `INV-AC-677 the allocated lines of a foreign document sum exactly to the stated SGD totals`() {
        val ls = listOf(line(1, "100.01", "9.01"), line(2, "200.02", "18.02"), line(3, "33.33", "3.33"))
        val s = stated("500.10", "45.00", "1.35")
        val r = allocated(SgdAllocator.allocate("USD", ls, s))
        r.fold(BigDecimal.ZERO) { x, l -> x + l.taxableAmountSgd } shouldBe s.totalExcludingTax
        r.fold(BigDecimal.ZERO) { x, l -> x + l.taxAmountSgd } shouldBe s.totalTax
    }
}
