package com.dynokore.gst.submission.domain.resubmission

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class ResubmissionRuleTest {
    private val tx = mapOf("taxCategoryCode" to "TX", "amount" to "100.00")
    private val zp = mapOf("taxCategoryCode" to "ZP", "amount" to "100.00")

    private fun decide(
        last: Map<String, String>,
        now: Map<String, String>,
        transmitted: Boolean = true,
    ) = ResubmissionRule.decide(ResubmissionFacts(transmitted, last, now))

    @Test
    fun `INV-AC-598 TX corrected to ZP is resubmitted with the changed field`() {
        decide(tx, zp) shouldBe Resubmit(listOf("taxCategoryCode"))
    }

    @Test
    fun `INV-AC-599 equal recorded fields give no resubmission`() {
        decide(tx, tx.toMap()) shouldBe NoResubmission(NoResubmissionReason.NO_RECORDED_CHANGE)
    }

    @Test
    fun `INV-AC-13099 changed field resubmits and equal fields do not`() {
        decide(tx, zp) shouldBe Resubmit(listOf("taxCategoryCode"))
        decide(tx, tx.toMap()) shouldBe NoResubmission(NoResubmissionReason.NO_RECORDED_CHANGE)
    }

    @Test
    fun `INV-AC-13099 facts declare exactly the three recorded-data fields`() {
        ResubmissionFacts::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet() shouldBe setOf("transmitted", "lastAcknowledged", "projected")
    }

    @Test
    fun `INV-AC-13099 a field only in projected is a change`() {
        decide(tx, tx + ("extra" to "1")) shouldBe Resubmit(listOf("extra"))
    }

    @Test
    fun `INV-AC-13099 a field only in lastAcknowledged is a change`() {
        decide(tx + ("extra" to "1"), tx) shouldBe Resubmit(listOf("extra"))
    }

    @Test
    fun `INV-AC-13099 an empty text against an absent field is a change`() {
        decide(tx, tx + ("note" to "")) shouldBe Resubmit(listOf("note"))
        decide(tx + ("note" to ""), tx) shouldBe Resubmit(listOf("note"))
    }

    @Test
    fun `INV-AC-13099 case and trailing zero differences are changes`() {
        decide(tx, tx + ("taxCategoryCode" to "tx")) shouldBe Resubmit(listOf("taxCategoryCode"))
        decide(tx, tx + ("amount" to "100.0")) shouldBe Resubmit(listOf("amount"))
    }

    @Test
    fun `INV-AC-13099 changed fields are sorted whatever the insertion order`() {
        val last = linkedMapOf("b" to "1", "a" to "1", "c" to "1")
        val now = linkedMapOf("c" to "2", "b" to "2", "a" to "1")
        decide(last, now) shouldBe Resubmit(listOf("b", "c"))
        decide(now, last) shouldBe Resubmit(listOf("b", "c"))
    }

    @Test
    fun `INV-AC-13099 not transmitted gives NOT_TRANSMITTED even with differing maps`() {
        decide(tx, zp, transmitted = false) shouldBe NoResubmission(NoResubmissionReason.NOT_TRANSMITTED)
    }

    @Test
    fun `INV-AC-13099 empty baseline gives NO_ACKNOWLEDGED_BASELINE`() {
        decide(emptyMap(), tx) shouldBe Undecided(ResubmissionUndecidedReason.NO_ACKNOWLEDGED_BASELINE)
    }

    @Test
    fun `INV-AC-13099 a blank field name is undecided even when not transmitted`() {
        for (name in listOf("", " ")) {
            for (transmitted in listOf(true, false)) {
                decide(tx + (name to "x"), tx, transmitted) shouldBe
                    Undecided(ResubmissionUndecidedReason.BLANK_FIELD_NAME)
                decide(tx, tx + (name to "x"), transmitted) shouldBe
                    Undecided(ResubmissionUndecidedReason.BLANK_FIELD_NAME)
            }
        }
    }

    @Test
    fun `INV-AC-13099 a blank name with an empty baseline is BLANK_FIELD_NAME`() {
        decide(emptyMap(), mapOf(" " to "x")) shouldBe Undecided(ResubmissionUndecidedReason.BLANK_FIELD_NAME)
    }

    @Test
    fun `INV-AC-13099 null facts are undecided`() {
        ResubmissionRule.decide(null) shouldBe Undecided(ResubmissionUndecidedReason.FACTS_MISSING)
    }

    @Test
    fun `INV-AC-13099 the input maps are not modified`() {
        val last = linkedMapOf("b" to "1", "a" to "1")
        val now = linkedMapOf("b" to "2", "z" to "1")
        val lastCopy = LinkedHashMap(last)
        val nowCopy = LinkedHashMap(now)
        decide(last, now)
        last shouldBe lastCopy
        now shouldBe nowCopy
    }

    private val mainSource: String =
        java.io
            .File(
                "src/main/kotlin/com/dynokore/gst/submission/domain/resubmission/ResubmissionRule.kt",
            ).readText()

    @Test
    fun `INV-AC-13099 the rule source has no import, clock or system access`() {
        val code = mainSource.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        code.filter { it.trimStart().startsWith("import ") } shouldBe emptyList()
        for (token in listOf("java.", "Instant", "Clock", "LocalDate", "System.", "currentTimeMillis", "nanoTime")) {
            code.filter { it.contains(token) } shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-13099 the rule source holds no field name or category code`() {
        mainSource.contains("taxCategoryCode") shouldBe false
    }
}
