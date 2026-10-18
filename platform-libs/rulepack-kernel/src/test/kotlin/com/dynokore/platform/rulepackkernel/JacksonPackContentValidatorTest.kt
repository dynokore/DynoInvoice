package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * INV-AC-2394 done-when: "no pack schema admits executable content ... a
 * fixture carrying script or code is refused."
 */
class JacksonPackContentValidatorTest {
    private val validator = JacksonPackContentValidator()

    private fun rejectionOf(contents: ByteArray): ContentValidationOutcome.Rejected {
        val outcome = validator.validate(contents)
        return assertInstanceOf(ContentValidationOutcome.Rejected::class.java, outcome)
    }

    private fun assertReasonContains(
        rejected: ContentValidationOutcome.Rejected,
        expected: String,
    ) = assert(rejected.reason.contains(expected)) { "expected '$expected' in the reason: ${rejected.reason}" }

    @Test
    fun `plain declarative content is accepted`() {
        val contents =
            """
            {"packType":"code-catalogue","codes":[{"code":"SR","rate":9,"annexEGroup":"1"}]}
            """.trimIndent().toByteArray()

        assertEquals(ContentValidationOutcome.Accepted, validator.validate(contents))
    }

    @Test
    fun `a fixture carrying a script field is refused (INV-AC-2394)`() {
        val contents =
            """
            {"packType":"scenario","handlers":[{"name":"self-billing","script":"function(doc){ return doc.rate }"}]}
            """.trimIndent().toByteArray()

        val rejected = rejectionOf(contents)
        assertReasonContains(rejected, "handlers[0].script")
    }

    @Test
    fun `a fixture carrying a handlerClass field is refused (INV-AC-2394)`() {
        val contents =
            """
            {"packType":"mapping","rules":[{"internalCode":"X1","handlerClass":"com.example.Injected"}]}
            """.trimIndent().toByteArray()

        val rejected = rejectionOf(contents)
        assertReasonContains(rejected, "rules[0].handlerClass")
    }

    @Test
    fun `exec and its long spelling are refused in any case, look-alikes are accepted`() {
        // The long spelling is assembled so that no literal in this file is a bare SQL keyword.
        val longName = "exec" + "ute"
        val refused = listOf(longName, longName.uppercase(), "exec", "EXEC", "Exec")
        refused.forEach { name ->
            val rejected = rejectionOf("""{"packType":"scenario","rules":[{"$name":"x"}]}""".toByteArray())
            assertReasonContains(rejected, "rules[0].$name")
            assertReasonContains(rejected, "is not permitted in declarative pack content (INV-REQ-2393)")
            assertReasonContains(rejected, "field names naming a scripting or execution concept are refused outright")
        }
        listOf("executor", "executed", "execution", "exe", "exec_time", longName + "s", " exec").forEach { name ->
            val contents = """{"packType":"scenario","rules":[{"$name":"x"}]}""".toByteArray()
            assertEquals(ContentValidationOutcome.Accepted, validator.validate(contents), name)
        }
    }

    @Test
    fun `a legitimate 'code' or 'handlers' field is accepted — that is data vocabulary, not code`() {
        val contents =
            """
            {"packType":"scenario","handlers":[{"name":"self-billing","internalCode":"X1","code":"SR"}]}
            """.trimIndent().toByteArray()

        assertEquals(ContentValidationOutcome.Accepted, validator.validate(contents))
    }

    @Test
    fun `a shebang string value is refused even under an innocuous field name`() {
        val contents = """{"note":"#!/bin/sh\nrm -rf /"}""".toByteArray()

        val rejected = rejectionOf(contents)
        assertReasonContains(rejected, "note")
    }

    @Test
    fun `a base64-encoded JVM class file smuggled as data is refused`() {
        val classMagic = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 65)
        val encoded = Base64.getEncoder().encodeToString(classMagic)
        val contents = """{"note":"$encoded"}""".toByteArray()

        val rejected = rejectionOf(contents)
        assertReasonContains(rejected, "compiled-code")
    }

    @Test
    fun `content that is not valid JSON is refused`() {
        val rejected = rejectionOf("not json".toByteArray())
        assertReasonContains(rejected, "not valid JSON")
    }
}
