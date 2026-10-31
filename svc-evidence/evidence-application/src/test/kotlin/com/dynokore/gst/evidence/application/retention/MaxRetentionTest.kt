package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.configresolver.VariationPointKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MaxRetentionTest {
    private fun figure(
        stored: String?,
        default: Int = 5,
    ): MaxRetention {
        val maximum =
            object : OptionValueMaximum {
                override fun maximum(
                    key: VariationPointKey,
                    option: String,
                ): BigDecimal? =
                    stored?.takeIf { key == RetentionPolicy.KEY && option == RetentionPolicy.LONGER }?.let(::BigDecimal)
            }
        return MaxRetention(maximum, default)
    }

    private fun duration(
        stored: String?,
        default: Int = 5,
    ): String = figure(stored, default).duration()

    @Test
    fun `INV-AC-6532 the figure is the larger of the default and the stored maximum, as P n Y`() {
        duration(null) shouldBe "P5Y"
        duration("3") shouldBe "P5Y"
        duration("10") shouldBe "P10Y"
        duration("10.00") shouldBe "P10Y"
        duration("7.5") shouldBe "P7.5Y"
        duration("7.25") shouldBe "P7.25Y"
        duration("7.254") shouldBe "P7.25Y"
        duration("100") shouldBe "P100Y"
    }

    @Test
    fun `the digest period is whole years, a fractional figure rounded up and never down`() {
        figure(null).period().years shouldBe 5
        figure("10").period().years shouldBe 10
        figure("7.5").period().years shouldBe 8
        figure("7.001").period().years shouldBe 8
        figure("7.0").period().years shouldBe 7
        figure("3").period().years shouldBe 5
    }
}
