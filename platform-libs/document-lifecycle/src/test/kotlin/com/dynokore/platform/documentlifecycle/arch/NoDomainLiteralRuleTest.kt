package com.dynokore.platform.documentlifecycle.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The library is mechanism only (INV-MOD-2741): no state code, return form, GST term or Singapore rule appears under
 * `src/main` or `src/impl`. Every Kotlin file there is scanned for the literals below, comments and strings included;
 * those literals appear under `src/test` only. A hit anywhere is a finding (no allowlist: none is justified).
 */
class NoDomainLiteralRuleTest {
    private val forbidden =
        listOf(
            "ACKNOWLEDGED",
            "QUEUED",
            "SENT",
            "RETRY",
            "READY",
            "CANCELLED",
            "SUPERSEDED",
            "GST",
            "IRAS",
            "NON_MONETARY",
            "MONETARY",
            "IDENTITY",
            "PERIOD_SHIFTING",
            "STRUCTURAL",
            "SUB-015",
            "SUB-016",
            "PER-006",
            "ING-012",
            "categoryCode",
            "glAccount",
        )

    private fun findings(root: File): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
            .toList()

    // A literal that contains another (NON_MONETARY holds MONETARY) is reported for both.
    private fun expected(literal: String) = forbidden.filter { literal.contains(it) }.map { "A.kt: $it" }

    private fun module(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, "document-lifecycle")
    }

    @Test
    fun `main and impl hold no state code, GST or IRAS term`() {
        assertEquals(emptyList<String>(), findings(File(module(), "src/main")) + findings(File(module(), "src/impl")))
    }

    @Test
    fun `probe - a literal added to a file is a finding, in code, comment or string`(
        @TempDir dir: File,
    ) {
        forbidden.forEach { literal ->
            File(dir, "A.kt").writeText("// $literal\n")
            assertEquals(expected(literal), findings(dir), literal)
            File(dir, "A.kt").writeText("val x = \"a${literal}b\"\n")
            assertEquals(expected(literal), findings(dir), literal)
        }
    }
}
