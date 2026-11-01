package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpstreamFunctionTest {
    private val expected =
        mapOf(
            DocumentFunction.INVOICE to UpstreamFunction.BILL_FOR_PAYMENT,
            DocumentFunction.BILL_FOR_PAYMENT to UpstreamFunction.BILL_FOR_PAYMENT,
            DocumentFunction.CREDIT_NOTE to UpstreamFunction.ADJUSTMENT,
            DocumentFunction.DEBIT_NOTE to UpstreamFunction.ADJUSTMENT,
            DocumentFunction.ADJUSTMENT to UpstreamFunction.ADJUSTMENT,
            DocumentFunction.NOT_A_BILL to UpstreamFunction.NOT_A_BILL,
        )

    @Test
    fun `INV-AC-13668 each of the six values maps to the stated member`() {
        for ((function, member) in expected) function.toUpstreamFunction() shouldBe member
        expected.size shouldBe 6
    }

    @Test
    fun `INV-AC-13668 every DocumentFunction member has a mapping`() {
        DocumentFunction.entries.forEach { expected.containsKey(it) shouldBe true }
        DocumentFunction.entries.map { it.toUpstreamFunction() }.size shouldBe DocumentFunction.entries.size
        UpstreamFunction.entries.map { it.name } shouldBe listOf("BILL_FOR_PAYMENT", "ADJUSTMENT", "NOT_A_BILL")
    }

    @Test
    fun `INV-AC-13668 the mapping source has no default branch`() {
        val file =
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .map {
                    java.io.File(
                        it,
                        "canonical-domain/src/main/kotlin/com/dynokore/gst/canonical/domain/derivation/UpstreamFunction.kt",
                    )
                }.first { it.isFile }
        val code =
            file.readLines().filterNot {
                it.trim().startsWith("*") || it.trim().startsWith("/*") ||
                    it.trim().startsWith("//")
            }
        code.any { Regex("\\belse\\b").containsMatchIn(it) } shouldBe false
    }
}
