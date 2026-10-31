package com.dynokore.gst.evidence.retention

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * No source of evidence-domain or evidence-application holds a retention period of its own: the default years are
 * read from the catalogue default of VP-43 (INV-REQ-638 "not hard-coded to five years"; Keel criterion on resolution).
 * The scan is deny-by-default over the code with comments removed: a number literal of five (or its spelling as days,
 * months or weeks), a name `YEARS_<n>` outside a string, or an ISO period `P<n>Y` anywhere is a finding.
 */
class RetentionLiteralScanTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).parent
    private val modules = listOf("evidence-domain", "evidence-application")
    private val number =
        Regex("""(?<![A-Za-z0-9_.])(5|1825|1826|1827|60|260|261|262)[lLuU]*(?![A-Za-z0-9_.])|YEARS_\d""")
    private val iso = Regex("""P\d+Y""")
    private val token = Regex("\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|/\\*")

    /** [text] without comments (block comments nest); a string is kept whole or reduced to `""`. */
    fun code(
        text: String,
        keepStrings: Boolean = false,
    ): String {
        val out = StringBuilder()
        var at = 0
        while (true) {
            val match = token.find(text, at) ?: break
            out.append(text, at, match.range.first)
            at = match.range.last + 1
            when {
                match.value == "/*" -> at = endOfBlockComment(text, at)
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

    /**
     * Reviewed exceptions, each an exact finding string with its reason (equality, never a prefix or a glob):
     * `MAX_SUBJECT_REF = 60` is the length limit of a privacy case's subject reference, not a retention period.
     */
    private val allowed =
        mapOf(
            "evidence-domain/kotlin/com/dynokore/gst/evidence/domain/cases/CaseRecord.kt: ${number.pattern}: 60" to
                "the maximum length of a subject reference (INV-T-11704)",
        )

    private fun findings(text: String): List<String> =
        listOf(number to code(text), iso to code(text, keepStrings = true)).flatMap { (pattern, source) ->
            pattern.findAll(source).map { "${pattern.pattern}: ${it.value}" }.toList()
        }

    @Test
    fun `the evidence-domain and evidence-application sources hold no retention literal`() {
        val found =
            modules.flatMap { module ->
                val dir = root.resolve(module).resolve("src/main")
                Files.walk(dir).use { files ->
                    files.filter { it.toString().endsWith(".kt") }.toList().flatMap { file ->
                        findings(Files.readString(file)).map { "$module/${dir.relativize(file)}: $it" }
                    }
                }
            }
        (found - allowed.keys).shouldBeEmpty()
        found.count { it in allowed } shouldBe allowed.size
    }

    @Test
    fun `an allowance covers one exact finding and not a look-alike file or number`() {
        val lookAlikes =
            listOf(
                "evidence-domain/kotlin/com/dynokore/gst/evidence/domain/cases/CaseRecord2.kt: ${number.pattern}: 60",
                "evidence-application/kotlin/com/dynokore/gst/evidence/domain/cases/CaseRecord.kt:" +
                    " ${number.pattern}: 60",
                "evidence-domain/kotlin/com/dynokore/gst/evidence/domain/cases/CaseRecord.kt: ${number.pattern}: 5",
            )
        lookAlikes.none { it in allowed } shouldBe true
    }

    @Test
    fun `the scan finds a five-year constant wherever it hides and ignores comments and plain strings`() {
        findings("val years = 5").size shouldBe 1
        findings("val years = 5L").size shouldBe 1
        findings("val d = 1825; val x = 1").size shouldBe 1
        findings("const val DEFAULT = \"P5Y\"").size shouldBe 1
        findings("val o = YEARS_5").size shouldBe 1
        findings("val s = \"/*\"; val b = 5 // five years").size shouldBe 1
        findings("/* 5 /* 5 */ 5 */ val a = 1").size shouldBe 0
        findings("// 5 years\nval a = \"5\"").size shouldBe 0
        findings("val a = 15; val b = x5; val c = 5.5; val d = 50").size shouldBe 0
    }
}
