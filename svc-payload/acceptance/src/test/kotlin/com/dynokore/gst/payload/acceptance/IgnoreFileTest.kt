package com.dynokore.gst.payload.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class IgnoreFileTest {
    private val allowed = listOf(".gradle/", ".kotlin/", "*.hprof", "/build/", "/*/build/")

    private fun text(lines: List<String>) = lines.joinToString("\n") + "\n"

    @Test
    fun `INV-AC-23049 the root ignore file equals the allowed list`() {
        val file = File(System.getProperty("gst.root.dir"), ".gitignore")
        findings(file.takeIf { it.isFile }?.readText()).shouldBeEmpty()
        findings(text(allowed)).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-23049 an unanchored build pattern is a finding`() {
        val found = findings(text(allowed.map { if (it == "/build/") "build/" else it }))
        found.shouldNotBeEmpty()
        found.any { it.contains("build/") } shouldBe true
    }

    @Test
    fun `INV-AC-23049 a missing output pattern is a finding`() {
        val found = findings(text(allowed - "/*/build/"))
        found.shouldNotBeEmpty()
        found.any { it.contains("/*/build/") } shouldBe true
    }

    @Test
    fun `INV-AC-23049 an added negation is a finding`() {
        val found = findings(text(allowed + "!**/src/**/build/"))
        found.shouldNotBeEmpty()
        found.any { it.contains("!**/src/**/build/") } shouldBe true
    }

    @Test
    fun `INV-AC-23049 a null text is a finding`() {
        findings(null).shouldNotBeEmpty()
    }

    companion object {
        /** Deny by default: the non-blank lines must equal the allowed list exactly, in order. */
        fun findings(text: String?): List<String> {
            if (text == null) return listOf("root .gitignore is missing or unreadable")
            val lines = text.lines().filter { it.isNotBlank() }
            val allowedLines = listOf(".gradle/", ".kotlin/", "*.hprof", "/build/", "/*/build/")
            val out = mutableListOf<String>()
            lines.filter { it !in allowedLines }.forEach { out += "unexpected line: $it" }
            allowedLines.filter { it !in lines }.forEach { out += "missing line: $it" }
            if (out.isEmpty() && lines != allowedLines) out += "lines differ in order or count: $lines"
            return out
        }
    }
}
