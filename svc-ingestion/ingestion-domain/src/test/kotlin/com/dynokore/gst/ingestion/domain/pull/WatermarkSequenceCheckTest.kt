package com.dynokore.gst.ingestion.domain.pull

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WatermarkSequenceCheckTest {
    private fun check(
        last: Long?,
        vararg observed: Long,
        gapless: Boolean = true,
    ) = WatermarkSequenceCheck.check(gapless, last, observed.toList())

    private fun gap(vararg missing: LongRange) = SequenceVerdict.Gap("ING-008", missing.toList())

    @Test
    fun `INV-AC-14107 not gapless is not checked even with a hole`() {
        check(10, 11, 13, gapless = false) shouldBe SequenceVerdict.NotChecked
    }

    @Test
    fun `INV-AC-14107 unbroken run after lastChecked is continuous`() {
        check(10, 11, 12, 13) shouldBe SequenceVerdict.Continuous
    }

    @Test
    fun `INV-AC-14107 one hole is reported as ING-008`() {
        check(10, 11, 13) shouldBe gap(12L..12L)
    }

    @Test
    fun `INV-AC-14107 several holes are listed in ascending order`() {
        check(10, 11, 14, 17) shouldBe gap(12L..13L, 15L..16L)
    }

    @Test
    fun `INV-AC-14107 overlap at and below lastChecked is not a gap`() {
        check(10, 5, 10, 11) shouldBe SequenceVerdict.Continuous
    }

    @Test
    fun `INV-AC-14107 number at lastChecked then a hole`() {
        check(10, 10, 12) shouldBe gap(11L..11L)
    }

    @Test
    fun `INV-AC-14107 repeated numbers change nothing`() {
        check(10, 11, 11, 12) shouldBe SequenceVerdict.Continuous
    }

    @Test
    fun `INV-AC-14107 descending order is sorted first`() {
        check(10, 13, 11) shouldBe gap(12L..12L)
    }

    @Test
    fun `INV-AC-14107 without lastChecked the run starts at the smallest observed`() {
        check(null, 4, 5, 7) shouldBe gap(6L..6L)
    }

    @Test
    fun `INV-AC-14107 empty observed is continuous`() {
        check(10) shouldBe SequenceVerdict.Continuous
        check(null) shouldBe SequenceVerdict.Continuous
    }

    @Test
    fun `INV-AC-14107 a single number above lastChecked leaves the run before it missing`() {
        check(10, 12) shouldBe gap(11L..11L)
    }
}
