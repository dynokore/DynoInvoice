package com.dynokore.gst.aggregation.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AggregateIntakeCheckTest {
    @Test
    fun `INV-AC-447 a type other than POS, STI or PCP is refused with ING-006, terminal and without override`() {
        listOf("B2B", "INVOICE", "pos", " POS", "POS ", "", "PCP,POS", "POS2", null).forEach { offered ->
            val verdict = AggregateIntakeCheck.check(offered).shouldBeInstanceOf<IntakeVerdict.Refused>()
            verdict.refusal.code shouldBe "ING-006"
            verdict.refusal.terminal shouldBe true
            verdict.refusal.overridable shouldBe false
        }
    }

    @Test
    fun `POS, STI and PCP are accepted`() {
        AggregateType.entries.forEach { type ->
            AggregateIntakeCheck.check(type.name) shouldBe IntakeVerdict.Accepted(type)
        }
        AggregateType.entries.map { it.name } shouldBe listOf("POS", "STI", "PCP")
    }

    @Test
    fun `INV-AC-1572 an aggregate whose detail holds one ordinary B2B invoice is blocked with ING-006`() {
        val verdict = AggregateIntakeCheck.checkDetail(listOf("POS", "POS", "B2B", "POS"))
        val refused = verdict.shouldBeInstanceOf<IntakeVerdict.Refused>()
        refused.refusal.code shouldBe "ING-006"
        refused.refusal.terminal shouldBe true
        refused.refusal.overridable shouldBe false
        refused.refusal.offered shouldBe "B2B"
    }

    @Test
    fun `a detail of only POS, STI and PCP transactions is not blocked, and an empty detail is not an offender`() {
        AggregateIntakeCheck.checkDetail(listOf("POS", "STI", "PCP")) shouldBe null
        AggregateIntakeCheck.checkDetail(emptyList()) shouldBe null
        AggregateIntakeCheck.checkDetail(listOf("POS", null)).shouldBeInstanceOf<IntakeVerdict.Refused>()
    }
}
