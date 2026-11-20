package com.dynokore.gst.evidence.bootstrap

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * No main source of the four evidence modules holds the response period of VP-63 as a literal 30 (INV-REQ-1401,
 * INV-Q-6836 A: the pack period is read from the catalogue default, never written in the service). The scan is
 * deny-by-default over the code with comments removed: a line that names `responseDays` or `packDays` (as a name or in
 * a string) and holds the number 30 on its own (not inside `PACK_DEFAULT_30D` or `x30`) is a finding.
 */
class PrivacyLiteralScanTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).parent
    private val modules = listOf("evidence-domain", "evidence-application", "evidence-adapters", "evidence-bootstrap")
    private val subject = Regex("""\b(responseDays|packDays)\b""")
    private val number = Regex("""(?<![A-Za-z0-9_.])30[lLuU]*(?![A-Za-z0-9_.])""")
    private val token = Regex("\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|/\\*")

    /** [text] without comments (block comments nest); a string is kept whole or reduced to `""`. */
    private fun code(
        text: String,
        keepStrings: Boolean,
    ): String {
        val out = StringBuilder()
        var at = 0
        while (true) {
            val match = token.find(text, at) ?: break
            out.append(text, at, match.range.first)
            at = match.range.last + 1
            when {
                match.value == "/*" -> {
                    val end = endOfBlockComment(text, at)
                    out.append("\n".repeat(text.substring(at, end).count { it == '\n' })) // keep the line numbers
                    at = end
                }
                match.value.startsWith("//") -> Unit
                else -> out.append(if (keepStrings) match.value else "\"\"")
            }
        }
        return out.append(text, at, text.length).toString()
    }

    private fun endOfBlockComment(
        text: String,
        from: Int,
    ): Int {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text.substring(i, minOf(i + 2, text.length))) {
                "/*" -> depth++.also { i += 2 }
                "*/" -> depth--.also { i += 2 }
                else -> i++
            }
        }
        return i
    }

    private fun findings(text: String): List<Int> {
        val named = code(text, keepStrings = true).lines()
        val numbered = code(text, keepStrings = false).lines()
        return named.indices.filter { subject.containsMatchIn(named[it]) && number.containsMatchIn(numbered[it]) }
    }

    private fun scan(base: Path): List<String> =
        modules.flatMap { module ->
            val dir = base.resolve(module).resolve("src/main")
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.walk(dir).use { files ->
                    files.filter { it.toString().endsWith(".kt") }.toList().flatMap { file ->
                        findings(Files.readString(file)).map { "$module/${dir.relativize(file)}:${it + 1}" }
                    }
                }
            }
        }

    @Test
    fun `INV-AC-10587 no main source of the four modules holds the literal 30 on a line with responseDays or packDays`() {
        scan(root).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-10587 a scratch file with val packDays = 30 in any of the four main directories fails and in src test does not`(
        @TempDir tmp: Path,
    ) {
        modules.forEach { module ->
            val file = tmp.resolve(module).resolve("src/main/kotlin/Scratch.kt")
            Files.createDirectories(file.parent)
            Files.writeString(file, "val packDays = 30\n")
            scan(tmp).size shouldBe 1
            Files.delete(file)
            scan(tmp).size shouldBe 0
            val test = tmp.resolve(module).resolve("src/test/kotlin/Scratch.kt")
            Files.createDirectories(test.parent)
            Files.writeString(test, "val packDays = 30\n")
            scan(tmp).size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-10587 the scan finds the literal wherever it hides and ignores comments and look-alike names`() {
        findings("val responseDays = 30").size shouldBe 1
        findings("val packDays = 30L").size shouldBe 1
        findings("val m = mapOf(\"responseDays\" to 30)").size shouldBe 1
        findings("val a = 1; val packDays = 30").size shouldBe 1
        findings("val packDays = 30 // thirty").size shouldBe 1
        findings("// val packDays = 30\nval a = 1").size shouldBe 0
        findings("/* /* packDays 30 */ packDays 30 */ val a = 1").size shouldBe 0
        findings("val packDays = 300; val responseDays = x30; val s = \"PACK_DEFAULT_30D\"").size shouldBe 0
        findings("val packDays = 30.5").size shouldBe 0
        findings("val packDaysX = 30; val myresponseDays = 30").size shouldBe 0
        findings("val a = \"30\"; val packDays = 1").size shouldBe 0
    }
}
