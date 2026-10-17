package com.dynokore.platform.reconciliationengine.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The library is mechanism only (INV-MOD-2743): no category code, form type, GST term or Singapore rule appears under
 * `src/main` or `src/impl`. Every Kotlin file there is scanned for the literals below, comments and strings included;
 * those literals appear under `src/test` only. A hit anywhere is a finding (no allowlist: none is justified).
 */
class NoDomainLiteralRuleTest {
    private val forbidden =
        listOf("EXCLUDED_TXN", "PRE_OBLIGATION", "OUTSIDE_BY_RULE", "GST", "IRAS", "F5", "F7", "F8", "BOX_3", "PER-003")

    private fun findings(root: File): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
            .toList()

    private fun module(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, "reconciliation-engine")
    }

    @Test
    fun `main and impl hold no category code, form type, GST or IRAS term`() {
        assertEquals(emptyList<String>(), findings(File(module(), "src/main")) + findings(File(module(), "src/impl")))
    }

    @Test
    fun `probe - a literal added to a file is a finding, in code, comment or string`(
        @TempDir dir: File,
    ) {
        forbidden.forEach { literal ->
            File(dir, "A.kt").writeText("// $literal\n")
            assertEquals(listOf("A.kt: $literal"), findings(dir), literal)
            File(dir, "A.kt").writeText("val x = \"a${literal}b\"\n")
            assertEquals(listOf("A.kt: $literal"), findings(dir), literal)
        }
    }
}
