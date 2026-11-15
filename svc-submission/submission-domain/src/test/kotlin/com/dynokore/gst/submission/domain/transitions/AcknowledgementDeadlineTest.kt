package com.dynokore.gst.submission.domain.transitions

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class AcknowledgementDeadlineTest {
    private val a = Instant.parse("2026-03-01T08:00:00Z")
    private val t2 = Duration.ofMinutes(120)
    private val byThreshold = Instant.parse("2026-03-01T10:00:00Z")

    @Test
    fun `INV-AC-22760 cap null gives accepted plus threshold`() {
        AcknowledgementDeadline.of(a, t2, null) shouldBe byThreshold
    }

    @Test
    fun `INV-AC-22760 cap before gives the cap`() {
        val cap = Instant.parse("2026-03-01T09:00:00Z")
        AcknowledgementDeadline.of(a, t2, cap) shouldBe cap
    }

    @Test
    fun `INV-AC-22760 cap after gives accepted plus threshold`() {
        AcknowledgementDeadline.of(a, t2, Instant.parse("2026-03-05T00:00:00Z")) shouldBe byThreshold
    }

    @Test
    fun `INV-AC-22760 cap equal gives that instant`() {
        AcknowledgementDeadline.of(a, t2, byThreshold) shouldBe byThreshold
    }

    @Test
    fun `INV-AC-22760 threshold zero throws naming threshold`() {
        assertThrows<IllegalArgumentException> { AcknowledgementDeadline.of(a, Duration.ZERO, null) }
            .message!! shouldContain "threshold"
    }

    @Test
    fun `INV-AC-22760 threshold negative throws naming threshold`() {
        assertThrows<IllegalArgumentException> { AcknowledgementDeadline.of(a, Duration.ofMinutes(-1), null) }
            .message!! shouldContain "threshold"
    }
}
