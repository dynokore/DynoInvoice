package com.dynokore.gst.consoleBff.domain.masking

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ContentMaskTest {
    private val one =
        listOf(
            ViewField("id", FieldClass.IDENTIFIER, "INV-2026-001"),
            ViewField("state", FieldClass.STATE, "ACKNOWLEDGED"),
            ViewField("trace", FieldClass.TRACE, "rule R-17 applied"),
            ViewField("total", FieldClass.AMOUNT, "1090.00"),
            ViewField("buyer", FieldClass.NAME, "Acme Pte Ltd"),
            ViewField("addr", FieldClass.ADDRESS, "1 Raffles Place"),
        )

    @Test
    fun `INV-AC-15923 allowed returns every field unchanged`() {
        maskView(one, true) shouldBe one
    }

    @Test
    fun `INV-AC-15923 denied masks amount name and address exactly`() {
        val out = maskView(one, false)
        out.map { it.value } shouldBe
            listOf("INV-2026-001", "ACKNOWLEDGED", "rule R-17 applied", MASKED_VALUE, MASKED_VALUE, MASKED_VALUE)
        out.map { it.name } shouldBe one.map { it.name }
        out.map { it.fieldClass } shouldBe one.map { it.fieldClass }
    }

    @Test
    fun `INV-AC-15923 null empty short and long values mask to the same text`() {
        val inputs = listOf(null, "", "7", "9".repeat(400))
        for (v in inputs) {
            val out = maskView(listOf(ViewField("a", FieldClass.AMOUNT, v), ViewField("n", FieldClass.NAME, v)), false)
            out.map { it.value } shouldBe listOf(MASKED_VALUE, MASKED_VALUE)
        }
        MASKED_VALUE.length shouldBe 3
        MASKED_VALUE shouldNotContain "7"
        MASKED_VALUE shouldNotContain "9"
    }

    @Test
    fun `INV-AC-15923 empty list gives empty list for both decisions`() {
        maskView(emptyList(), true) shouldBe emptyList()
        maskView(emptyList(), false) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-15923 order and size are unchanged for 50 mixed fields`() {
        val classes = FieldClass.entries
        val fields = (0 until 50).map { ViewField("f$it", classes[it % classes.size], "v$it") }
        for (allowed in listOf(true, false)) {
            val out = maskView(fields, allowed)
            out.size shouldBe 50
            out.map { it.name } shouldBe fields.map { it.name }
            out.map { it.fieldClass } shouldBe fields.map { it.fieldClass }
        }
    }

    @Test
    fun `INV-AC-15923 input list and elements are unchanged after the call`() {
        val input = one.toMutableList()
        val copy = one.toList()
        maskView(input, false)
        maskView(input, true)
        input shouldBe copy
    }

    @Test
    fun `INV-AC-15923 the class decides not the name`() {
        val f = listOf(ViewField("amount", FieldClass.IDENTIFIER, "A-1"), ViewField("x", FieldClass.AMOUNT, "5"))
        maskView(f, false) shouldBe listOf(f[0], ViewField("x", FieldClass.AMOUNT, MASKED_VALUE))
    }

    @Test
    fun `INV-AC-15923 FieldClass has exactly six constants in order`() {
        FieldClass.entries.map { it.name } shouldBe
            listOf("IDENTIFIER", "STATE", "TRACE", "AMOUNT", "NAME", "ADDRESS")
    }
}
