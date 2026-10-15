package com.dynokore.platform.calendarandperiods.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * INV-REQ-707: no period start or end is computed from a frequency constant ("quarter = three months from the period
 * start" fails here). The guard is deny-by-default over resolved declarations, not a denylist of known-bad names, in
 * the production sources (`main` and `impl`):
 *  1. `FilingFrequency` is never constructed there: a frequency enters only as pack, history or request data.
 *  2. Nothing branches on a frequency (`when (...frequency...)`), so no per-frequency length can be spelt out.
 *  3. Every property, constructor parameter or function parameter or return type that mentions `FilingFrequency` must
 *     be in [ALLOWED], each with a one-line justification. Anything else, including a new
 *     `Map<FilingFrequency, Int>` of days or months, is a finding.
 * The allowlist is pinned to the real sources both ways: a stale entry (no such declaration) fails too, so a change of
 * the declared surface cannot pass unclassified.
 */
class FrequencyConstantRuleTest {
    /** `file:declaration` to why it may mention a frequency. */
    private val allowed =
        mapOf(
            "PeriodModel.kt:frequency" to "a history entry names the frequency in effect from a date (data)",
            "PeriodModel.kt:at" to "history lookup: returns the frequency in effect on a date",
            "CalendarPack.kt:windows" to "the pack's windows per frequency: explicit dates, no length",
            "CalendarPack.kt:windowsByFrequency" to "validated copy of the pack's windows per frequency",
            "CalendarPack.kt:windowContaining" to "looks up the pack's window holding a date for a frequency",
            "CalendarPack.kt:frequency" to "parameter of windowContaining",
        )

    private fun findings(
        files: List<KoFileDeclaration>,
        allowlist: Set<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        for (file in files) {
            val fileName = file.name + "." + file.extension
            val text = file.text
            val withoutDeclaration = text.replace(Regex("""value class FilingFrequency\s*\("""), "")
            if (Regex("""\bFilingFrequency\s*\(""").containsMatchIn(withoutDeclaration)) {
                out += "$fileName: constructs a FilingFrequency"
            }
            if (Regex("""\bwhen\s*\([^)]*[Ff]requency[^)]*\)""").containsMatchIn(text)) {
                out += "$fileName: branches on a frequency"
            }
            val declarations =
                file.properties(includeNested = true).map { it.name to it.text } +
                    file.functions(includeNested = true).flatMap { f ->
                        listOf(f.name to f.text.substringBefore("{").substringBefore("=")) +
                            f.parameters.map { p -> p.name to p.text }
                    } +
                    file.classes(includeNested = true).flatMap { c ->
                        c.primaryConstructor
                            ?.parameters
                            .orEmpty()
                            .map { p -> p.name to p.text }
                    }
            for ((name, declText) in declarations) {
                if (declText.contains("FilingFrequency") && "$fileName:$name" !in allowlist) {
                    out += "$fileName: $name mentions FilingFrequency but is not on the allowlist"
                }
            }
        }
        return out.distinct()
    }

    /** The production sources only: Konsist's module scope also holds this module's test files. */
    private fun realFiles(): List<KoFileDeclaration> =
        Konsist
            .scopeFromModule("calendar-and-periods", "main", "impl")
            .files
            .filter { "/src/main/" in it.path || "/src/impl/" in it.path }

    @Test
    fun `the real sources compute no period from a frequency constant`() {
        assertEquals(emptyList<String>(), findings(realFiles(), allowed.keys))
    }

    @Test
    fun `every allowlist entry names a declaration that exists`() {
        val seen = mutableSetOf<String>()
        for (file in realFiles()) {
            val fileName = file.name + "." + file.extension
            file.properties(includeNested = true).forEach {
                if (it.text.contains("FilingFrequency")) seen += "$fileName:${it.name}"
            }
            file.functions(includeNested = true).forEach { f ->
                if (f.text.substringBefore("{").contains("FilingFrequency")) seen += "$fileName:${f.name}"
                f.parameters.forEach { if (it.text.contains("FilingFrequency")) seen += "$fileName:${it.name}" }
            }
            file.classes(includeNested = true).forEach { c ->
                c.primaryConstructor?.parameters.orEmpty().forEach {
                    if (it.text.contains("FilingFrequency")) seen += "$fileName:${it.name}"
                }
            }
        }
        assertEquals(allowed.keys, seen)
    }

    @Test
    fun `probe - a FilingFrequency-to-days constant is a finding`(
        @TempDir dir: File,
    ) {
        val constants =
            listOf(
                "val DAYS = mapOf(FilingFrequency(\"Q\") to 91)",
                "val DAYS: Map<FilingFrequency, Int> = emptyMap()",
                "fun days(f: FilingFrequency): Int = when (f) { else -> 91 }",
                "const val QUARTER_DAYS = 91\nval QUARTER = FilingFrequency(\"Q\")",
            )
        constants.forEachIndexed { i, body ->
            val only = File(dir, "c$i").also { it.mkdirs() }
            File(only, "Offender.kt").writeText("package com.dynokore.platform.calendarandperiods\n\n$body\n")
            val found = findings(Konsist.scopeFromExternalDirectory(only.absolutePath).files, allowed.keys)
            assertTrue(found.isNotEmpty(), "no finding for: $body")
        }
    }

    @Test
    fun `an unclassified new declaration of the frequency type is a finding`(
        @TempDir dir: File,
    ) {
        File(dir, "Pack.kt").writeText(
            "package com.dynokore.platform.calendarandperiods\n\n" +
                "class Pack(val lengths: Map<FilingFrequency, List<String>>)\n",
        )

        val found = findings(Konsist.scopeFromExternalDirectory(dir.absolutePath).files, allowed.keys)

        assertTrue(found.any { it.contains("lengths") }, found.toString())
    }
}
