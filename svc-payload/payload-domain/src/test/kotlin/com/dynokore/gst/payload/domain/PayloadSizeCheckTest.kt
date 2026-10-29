package com.dynokore.gst.payload.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PayloadSizeCheckTest {
    private val small = PayloadLimits(100L, 3)
    private val block = SizeFinding("PAY-010", Severity.BLOCK)
    private val warn = SizeFinding("PAY-015", Severity.WARN)

    @Test
    fun `INV-AC-451 a document above 10 MB is blocked with PAY-010`() {
        val v = PayloadSizeCheck.check(10_000_001L, 10)
        v.blocked shouldBe true
        v.findings shouldBe listOf(block)
    }

    @Test
    fun `INV-AC-460 1200 lines under 10 MB is warned with PAY-015 and not blocked`() {
        val v = PayloadSizeCheck.check(9_000_000L, 1_200)
        v.findings shouldBe listOf(warn)
        v.blocked shouldBe false
    }

    @Test
    fun `byte boundary at the limit`() {
        PayloadSizeCheck.check(100L, 0, small).findings shouldBe emptyList()
        PayloadSizeCheck.check(101L, 0, small).findings shouldBe listOf(block)
    }

    @Test
    fun `line boundary at the recommendation`() {
        PayloadSizeCheck.check(0L, 3, small).findings shouldBe emptyList()
        PayloadSizeCheck.check(0L, 4, small).findings shouldBe listOf(warn)
    }

    @Test
    fun `both limits exceeded lists PAY-010 first and blocks`() {
        val v = PayloadSizeCheck.check(101L, 4, small)
        v.findings shouldBe listOf(block, warn)
        v.blocked shouldBe true
    }

    @Test
    fun `negative limits are rejected`() {
        shouldThrow<IllegalArgumentException> { PayloadLimits(-1L, 3) }
        shouldThrow<IllegalArgumentException> { PayloadLimits(100L, -1) }
    }

    @Test
    fun `the IRAS figures are pinned and 10 MB is decimal`() {
        PayloadSizeCheck.IRAS_FR_4_12 shouldBe PayloadLimits(10_000_000L, 999)
        PayloadSizeCheck.check(10_485_760L, 10).blocked shouldBe true
    }

    @Test
    fun `zero arguments give no finding and negative arguments are rejected`() {
        PayloadSizeCheck.check(0L, 0).findings shouldBe emptyList()
        shouldThrow<IllegalArgumentException> { PayloadSizeCheck.check(-1L, 0) }
        shouldThrow<IllegalArgumentException> { PayloadSizeCheck.check(0L, -1) }
    }

    @Test
    fun `codes are the catalogue codes`() {
        PayloadSizeCheck.PAY_010 shouldBe "PAY-010"
        PayloadSizeCheck.PAY_015 shouldBe "PAY-015"
    }
}
