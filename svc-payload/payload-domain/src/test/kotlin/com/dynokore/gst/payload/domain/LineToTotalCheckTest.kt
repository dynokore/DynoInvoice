package com.dynokore.gst.payload.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LineToTotalCheckTest {
    private fun d(s: String) = BigDecimal(s)

    private fun amounts(vararg s: String) = s.map { d(it) }

    private val pack = PackLineTolerance(d("0.05"), d("1.00"), "amount")

    private fun run(
        lines: List<BigDecimal>,
        total: String,
        tenant: String? = null,
        p: PackLineTolerance = pack,
    ) = LineToTotalCheck.check(lines, d(total), p, tenant?.let { d(it) })

    private fun LineToTotalVerdict.asVariance(): LineToTotalVerdict.Variance = this as LineToTotalVerdict.Variance

    private fun eq(
        actual: BigDecimal,
        expected: String,
    ) = (actual.compareTo(d(expected)) == 0) shouldBe true

    @Test
    fun `INV-AC-13178 a variance above the tolerance warns PAY-004 and keeps the stated total`() {
        val v = run(amounts("600.00", "400.07"), "1000.00").asVariance()
        v.code shouldBe "PAY-004"
        v.severity shouldBe Severity.WARN
        eq(v.lineSum, "1000.07")
        eq(v.variance, "0.07")
        eq(v.tolerance, "0.05")
        v.statedTotal shouldBe d("1000.00")
        run(amounts("600.00", "400.05"), "1000.00") shouldBe LineToTotalVerdict.WithinTolerance
    }

    @Test
    fun `INV-AC-13179 the stated total is used as stated and a small variance raises nothing`() {
        run(amounts("333.33", "333.33", "333.33"), "1000.00") shouldBe LineToTotalVerdict.WithinTolerance
        val v = run(amounts("500.00", "400.00"), "1000.00").asVariance()
        eq(v.variance, "100.00")
        v.statedTotal shouldBe d("1000.00")
        v.statedTotal.scale() shouldBe 2
    }

    @Test
    fun `INV-AC-13180 the tenant value wins when within the ceiling otherwise the pack default`() {
        val lines = amounts("600.00", "400.30")
        eq(run(lines, "1000.00").asVariance().tolerance, "0.05")
        run(lines, "1000.00", "0.50") shouldBe LineToTotalVerdict.WithinTolerance
        eq(run(lines, "1000.00", "0.20").asVariance().tolerance, "0.20")
        shouldThrow<IllegalArgumentException> { run(lines, "1000.00", "1.01") }
        for (tenant in listOf(null, "0.50", "1.01")) {
            shouldThrow<IllegalArgumentException> {
                run(lines, "1000.00", tenant, PackLineTolerance(d("0.05"), d("1.00"), "percent"))
            }
        }
    }

    @Test
    fun `variance equal to the tolerance is within and above it warns`() {
        run(amounts("1000.05"), "1000.00") shouldBe LineToTotalVerdict.WithinTolerance
        eq(run(amounts("1000.06"), "1000.00").asVariance().variance, "0.06")
    }

    @Test
    fun `1000_0 and 1000_00 compare equal`() {
        run(amounts("1000.0"), "1000.00", "0") shouldBe LineToTotalVerdict.WithinTolerance
    }

    @Test
    fun `tenant value 0 warns on a variance of 0_01 and the ceiling is accepted`() {
        eq(run(amounts("1000.01"), "1000.00", "0").asVariance().tolerance, "0")
        run(amounts("1000.90"), "1000.00", "1.00") shouldBe LineToTotalVerdict.WithinTolerance
    }

    @Test
    fun `unit other than amount is refused`() {
        for (unit in listOf("Amount", "percent", "")) {
            shouldThrow<IllegalArgumentException> {
                run(amounts("1.00"), "1.00", p = PackLineTolerance(d("0.05"), d("1.00"), unit))
            }
        }
    }

    @Test
    fun `invalid pack and tenant values are refused`() {
        shouldThrow<IllegalArgumentException> {
            run(amounts("1.00"), "1.00", p = PackLineTolerance(d("-0.01"), d("1.00"), "amount"))
        }
        shouldThrow<IllegalArgumentException> {
            run(amounts("1.00"), "1.00", p = PackLineTolerance(d("1.01"), d("1.00"), "amount"))
        }
        shouldThrow<IllegalArgumentException> { run(amounts("1.00"), "1.00", "-0.01") }
    }

    @Test
    fun `empty lines sum to zero`() {
        run(emptyList(), "0.00") shouldBe LineToTotalVerdict.WithinTolerance
        eq(run(emptyList(), "10.00").asVariance().variance, "10.00")
    }

    @Test
    fun `credit note with negative amounts uses the absolute variance`() {
        val v = run(amounts("-600.00", "-400.07"), "-1000.00").asVariance()
        eq(v.variance, "0.07")
        eq(v.lineSum, "-1000.07")
    }

    @Test
    fun `constants`() {
        LineToTotalCheck.PAY_004 shouldBe "PAY-004"
        LineToTotalCheck.AMOUNT shouldBe "amount"
    }
}
