package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The scanner of the no-code-constant gate (INV-REQ-1350; owner answers INV-Q-10904 and INV-Q-10906): after comments
 * are removed, a statement that names the safety margin, the alert thresholds, the filing frequency or the windows and
 * also holds an integer, a collection constructor or one of the frequency and unit names is a finding.
 */
object CalendarNoCodeScan {
    /** The five module directories of the gate, compared by equality. */
    val MODULES =
        listOf(
            "control-plane-adapters",
            "control-plane-application",
            "control-plane-bootstrap",
            "control-plane-contract",
            "control-plane-domain",
        )

    private val NAMES = listOf("safetymargin", "alertthreshold", "alertthresholds", "filingfrequency", "windows")
    private val LITERALS = setOf("QUARTERLY", "MONTHLY", "DAYS", "HOURS")
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val INT = Regex("(?<![A-Za-z0-9_])[0-9][A-Za-z0-9_]*")
    private val CALL = Regex("\\b(listOf|setOf|mapOf|arrayOf|intArrayOf)\\s*[(<]")
    private const val MARK = '\u0001'

    /** Code per line: comments blanked, each string literal replaced by MARK n MARK; the literals in [strings]. */
    private fun strip(
        text: String,
        strings: MutableList<String>,
    ): List<String> {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                depth > 0 -> {
                    if (text.startsWith("/*", i)) {
                        depth++
                        i += 2
                    } else if (text.startsWith("*/", i)) {
                        depth--
                        i += 2
                    } else {
                        if (c == '\n') out.append('\n')
                        i++
                    }
                }
                text.startsWith("/*", i) -> {
                    depth = 1
                    i += 2
                }
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it }
                    strings += text.substring(i + 3, end)
                    out.append(MARK).append(strings.size - 1).append(MARK)
                    out.append("\n".repeat(text.substring(i, minOf(end + 3, text.length)).count { it == '\n' }))
                    i = minOf(end + 3, text.length)
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    strings += text.substring(i + 1, minOf(j, text.length))
                    out.append(MARK).append(strings.size - 1).append(MARK)
                    i = j + 1
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    out.append("''")
                    i = j + 1
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString().split("\n")
    }

    /** `file:line` of every finding in [text]; [file] is only the label. */
    fun scan(
        file: String,
        text: String,
    ): List<String> {
        val strings = ArrayList<String>()
        val found = ArrayList<String>()
        strip(text, strings).forEachIndexed { index, line ->
            for (statement in line.split(";")) {
                val names = IDENT.findAll(statement).map { it.value.replace("_", "").lowercase() }.toList()
                if (names.none { n -> NAMES.any { it in n } }) continue
                val literal = strings.filterIndexed { n, _ -> statement.contains("$MARK$n$MARK") }.any { it in LITERALS }
                if (INT.containsMatchIn(statement.replace(Regex("$MARK[0-9]+$MARK"), "")) || CALL.containsMatchIn(statement) || literal) {
                    found += "$file:${index + 1}"
                }
            }
        }
        return found.distinct()
    }

    fun scanTree(root: File): Pair<Int, List<String>> {
        var files = 0
        val findings = ArrayList<String>()
        for (module in MODULES) {
            val main = root.resolve("$module/src/main")
            if (!main.isDirectory) continue
            main
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts", "java") }
                .forEach {
                    files++
                    findings += scan(it.relativeTo(root).path.replace(File.separatorChar, '/'), it.readText(Charsets.UTF_8))
                }
        }
        return files to findings
    }
}

class CalendarSeedNoCodeConstantTest {
    @Test
    fun `INV-AC-1727 no main source of the five modules holds a margin threshold frequency or window constant`() {
        val root = CalendarSeedFiles.repoRoot
        root.resolve("control-plane-adapters/src/main/kotlin").isDirectory shouldBe true
        val (files, findings) = CalendarNoCodeScan.scanTree(root)
        (files > 0) shouldBe true
        findings.joinToString("\n") shouldBe ""
    }

    @Test
    fun `INV-AC-1727 the probes of the scanner each fail naming the file and line`() {
        fun hit(line: String) = CalendarNoCodeScan.scan("F.kt", "package p\n$line\n") shouldBe listOf("F.kt:2")
        hit("const val SAFETY_MARGIN_DAYS = 3")
        hit("val alertThresholds = listOf(10, 5)")
        hit("val filingFrequency = \"MONTHLY\"")
        hit("val windows = intArrayOf(1, 2)")
        hit("val x = 1; val alertThreshold = 2")
        hit("/* c */ val safetyMargin = 3")
        hit("val a = \"x\"; val safetyMargin = \"DAYS\"")
    }

    @Test
    fun `INV-AC-1727 the look-alikes of the scanner pass`() {
        fun pass(text: String) = CalendarNoCodeScan.scan("F.kt", text) shouldBe emptyList()
        pass("val safetyMargin: SafetyMargin = contents.safetyMargin")
        pass("val retries = 3")
        pass("// const val SAFETY_MARGIN_DAYS = 3")
        pass("/* const val SAFETY_MARGIN_DAYS = 3\n /* nested */ val windows = listOf(1) */")
        pass("val message = \"safetyMargin 3 windows\"")
    }
}
