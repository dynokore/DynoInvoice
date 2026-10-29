package com.dynokore.gst.notification.domain.webhook

import com.dynokore.gst.notification.domain.webhook.MaximumDecision.Accepted
import com.dynokore.gst.notification.domain.webhook.MaximumDecision.Refused
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OperatorRetryMaximumTest {
    private val bounds = MaximumBounds(72, 720)
    private val default = OperatorMaximum(168)
    private val current = OperatorMaximum(168)

    @Test
    fun `INV-AC-12932 nothing set gives the default`() {
        OperatorRetryMaximum.effective(null, default, bounds) shouldBe OperatorMaximum(168)
    }

    @Test
    fun `INV-AC-12932 both bounds and a middle value are accepted`() {
        listOf(72L, 100L, 720L).forEach {
            OperatorRetryMaximum.set(OperatorMaximum(it), current, bounds) shouldBe Accepted(OperatorMaximum(it))
        }
    }

    @Test
    fun `INV-AC-12932 values just outside are refused and the current maximum stays`() {
        listOf(71L, 721L).forEach {
            OperatorRetryMaximum.set(OperatorMaximum(it), current, bounds) shouldBe
                Refused("POL-013", OperatorMaximum(168))
        }
    }

    @Test
    fun `configured value is returned`() {
        OperatorRetryMaximum.effective(OperatorMaximum(300), default, bounds) shouldBe OperatorMaximum(300)
    }

    @Test
    fun `a default outside the bounds is a configuration error`() {
        listOf(721L, 71L).forEach {
            shouldThrow<IllegalArgumentException> { OperatorRetryMaximum.effective(null, OperatorMaximum(it), bounds) }
        }
    }

    @Test
    fun `a configured value outside the bounds throws`() {
        shouldThrow<IllegalArgumentException> { OperatorRetryMaximum.effective(OperatorMaximum(721), default, bounds) }
    }

    @Test
    fun `invalid bounds and maximum are rejected`() {
        shouldThrow<IllegalArgumentException> { MaximumBounds(0, 10) }
        shouldThrow<IllegalArgumentException> { MaximumBounds(10, 9) }
        shouldThrow<IllegalArgumentException> { OperatorMaximum(0) }
    }
}
