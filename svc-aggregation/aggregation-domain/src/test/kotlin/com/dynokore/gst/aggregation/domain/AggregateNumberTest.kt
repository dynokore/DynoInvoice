package com.dynokore.gst.aggregation.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AggregateNumberTest {
    private val end = LocalDate.of(2027, 3, 31)

    @Test
    fun `INV-AC-1436 the number is AGG, entity, type, business unit, period end and sequence`() {
        AggregateNumber.of("E", AggregateType.POS, "B", end, 1) shouldBe
            NumberingResult.Numbered("AGG-E-POS-B-20270331-1")
    }

    @Test
    fun `INV-AC-1436 the same inputs give the same number and another sequence another number`() {
        val a = AggregateNumber.of("E", AggregateType.STI, "B", end, 7)
        a shouldBe AggregateNumber.of("E", AggregateType.STI, "B", end, 7)
        (a == AggregateNumber.of("E", AggregateType.STI, "B", end, 8)) shouldBe false
    }

    @Test
    fun `INV-AC-1436 the issue and transaction date is the period end`() {
        AggregateNumber.issueDate(end) shouldBe end
    }

    @Test
    fun `INV-AC-1436 a number longer than 64 characters is reported, one of exactly 64 is accepted`() {
        // "AGG-" + e + "-POS-" + "B" + "-20270331-1" = 4 + n + 5 + 1 + 11 = 21 + n
        AggregateNumber.of("E".repeat(43), AggregateType.POS, "B", end, 1) shouldBe
            NumberingResult.Numbered("AGG-${"E".repeat(43)}-POS-B-20270331-1")
        AggregateNumber.of("E".repeat(44), AggregateType.POS, "B", end, 1) shouldBe NumberingResult.TooLong(65)
    }

    @Test
    fun `INV-AC-1436 a 100 character number is reported with its full length, never truncated`() {
        AggregateNumber.of("E".repeat(40), AggregateType.POS, "B".repeat(40), end, 1) shouldBe
            NumberingResult.TooLong(100)
    }

    @Test
    fun `the sequence has no separator and no zero padding, the type is upper case`() {
        AggregateNumber.of("E", AggregateType.PCP, "B", end, 12) shouldBe
            NumberingResult.Numbered("AGG-E-PCP-B-20270331-12")
    }

    @Test
    fun `an empty short code and a sequence below 1 are refused`() {
        shouldThrow<IllegalArgumentException> { AggregateNumber.of("", AggregateType.POS, "B", end, 1) }
        shouldThrow<IllegalArgumentException> { AggregateNumber.of("E", AggregateType.POS, "", end, 1) }
        shouldThrow<IllegalArgumentException> { AggregateNumber.of("E", AggregateType.POS, "B", end, 0) }
    }
}
