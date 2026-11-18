package com.dynokore.gst.evidence.domain.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PriorEvidencePeriodRefTest {
    private fun of(date: String) = PriorEvidencePeriodRef.of(LocalDate.parse(date))

    @Test
    fun `INV-AC-24791 quarter boundaries give the calendar quarter`() {
        of("2026-01-15") shouldBe "2026-Q1"
        of("2026-03-31") shouldBe "2026-Q1"
        of("2026-04-01") shouldBe "2026-Q2"
        of("2026-06-30") shouldBe "2026-Q2"
        of("2026-07-01") shouldBe "2026-Q3"
        of("2026-12-31") shouldBe "2026-Q4"
    }

    @Test
    fun `INV-AC-24791 the year is zero padded to four digits`() {
        of("0999-02-01") shouldBe "0999-Q1"
    }

    @Test
    fun `INV-AC-24791 a leap day gives Q1`() {
        of("2024-02-29") shouldBe "2024-Q1"
    }

    @Test
    fun `INV-AC-24791 twelve months of one year take Q1 Q2 Q3 Q4 three times each`() {
        val quarters = (1..12).map { PriorEvidencePeriodRef.of(LocalDate.of(2026, it, 1)) }
        quarters shouldBe
            listOf(
                "2026-Q1",
                "2026-Q1",
                "2026-Q1",
                "2026-Q2",
                "2026-Q2",
                "2026-Q2",
                "2026-Q3",
                "2026-Q3",
                "2026-Q3",
                "2026-Q4",
                "2026-Q4",
                "2026-Q4",
            )
    }

    @Test
    fun `INV-AC-24791 a year outside 1 to 9999 is refused naming the year`() {
        shouldThrow<IllegalArgumentException> { of("+10000-01-01") }.message!!.contains("10000") shouldBe true
        shouldThrow<IllegalArgumentException> { PriorEvidencePeriodRef.of(LocalDate.of(0, 1, 1)) }
            .message!!
            .contains("0") shouldBe true
    }
}
