package com.dynokore.gst.notification.domain.alerting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ThresholdRuleTest {
    private val contract = ThresholdBounds(0, 365)
    private val narrow = ThresholdBounds(10, 20)

    private fun decide(
        requested: Int,
        default: Int,
        bounds: ThresholdBounds = contract,
    ) = ThresholdRule.decide(requested, default, bounds)

    private val refused =
        ThresholdDecision.Refused("POL-008", ThresholdRefusal.LOOSER_THAN_DEFAULT)

    @Test
    fun `INV-AC-2264 monthly filer later first alert than the 5 day default is refused`() {
        decide(4, 5) shouldBe refused
        decide(5, 5) shouldBe ThresholdDecision.Accepted(5)
        decide(6, 5) shouldBe ThresholdDecision.Accepted(6)
    }

    @Test
    fun `INV-AC-203 quarterly tighter is accepted and looser is refused`() {
        decide(12, 10) shouldBe ThresholdDecision.Accepted(12)
        decide(9, 10) shouldBe refused
        decide(10, 10) shouldBe ThresholdDecision.Accepted(10)
    }

    @Test
    fun `INV-AC-109 each filing frequency is judged on its own default`() {
        decide(7, 5) shouldBe ThresholdDecision.Accepted(7)
        decide(7, 10) shouldBe refused
    }

    @Test
    fun `zero and upper bound edges`() {
        decide(0, 0) shouldBe ThresholdDecision.Accepted(0)
        decide(0, 1) shouldBe refused
        decide(365, 365) shouldBe ThresholdDecision.Accepted(365)
    }

    @Test
    fun `values outside the default bounds are programming errors`() {
        shouldThrow<IllegalArgumentException> { decide(-1, 5) }
        shouldThrow<IllegalArgumentException> { decide(366, 5) }
        shouldThrow<IllegalArgumentException> { decide(5, -1) }
        shouldThrow<IllegalArgumentException> { decide(5, 366) }
    }

    @Test
    fun `bounds are read from the argument`() {
        shouldThrow<IllegalArgumentException> { decide(9, 15, narrow) }
        shouldThrow<IllegalArgumentException> { decide(21, 15, narrow) }
        shouldThrow<IllegalArgumentException> { decide(15, 9, narrow) }
        shouldThrow<IllegalArgumentException> { decide(15, 21, narrow) }
        decide(10, 10, narrow) shouldBe ThresholdDecision.Accepted(10)
        decide(20, 20, narrow) shouldBe ThresholdDecision.Accepted(20)
    }

    @Test
    fun `configured bounds may only tighten the defaults`() {
        ThresholdBounds.tightenedFrom(contract, ThresholdBounds(7, 180)) shouldBe ThresholdBounds(7, 180)
        ThresholdBounds.tightenedFrom(contract, contract) shouldBe contract
        shouldThrow<IllegalArgumentException> { ThresholdBounds.tightenedFrom(contract, ThresholdBounds(-1, 365)) }
        shouldThrow<IllegalArgumentException> { ThresholdBounds.tightenedFrom(contract, ThresholdBounds(0, 366)) }
        shouldThrow<IllegalArgumentException> { ThresholdBounds.tightenedFrom(contract, ThresholdBounds(-1, 366)) }
    }

    @Test
    fun `bounds with min above max are refused`() {
        shouldThrow<IllegalArgumentException> { ThresholdBounds(5, 4) }
    }

    @Test
    fun `code constant and refusal set`() {
        POL_LOOSER_THAN_DEFAULT shouldBe "POL-008"
        (ThresholdRefusal.entries.map { it.name }) shouldBe listOf("LOOSER_THAN_DEFAULT")
    }
}
