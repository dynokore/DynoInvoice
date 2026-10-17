package com.dynokore.platform.integrationcanon.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The library is mechanism only (INV-MOD-2744): every file under `src/main` and `src/impl` is scanned, whatever its
 * type, for the literals below (case-sensitive substring, comments and strings included). The literals appear
 * under `src/test` only. The gate fails closed: a missing root or an empty scan is a failure; no allowlist.
 */
class VocabularyGateTest {
    private val forbidden = listOf("PINT", "Peppol", "PAY-012", "GST", "IRAS", "Singapore", "Clock", "now(")

    private fun findings(root: File): List<String> {
        check(root.isDirectory) { "scan root ${root.path} is missing" }
        return root
            .walkTopDown()
            .filter { it.isFile }
            .flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
            .toList()
    }

    private fun module(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, "integration-canon")
    }

    @Test
    fun `main and impl hold none of the forbidden literals`() {
        val roots = listOf("src/main", "src/impl").map { File(module(), it) }
        roots.forEach { assertTrue(it.walkTopDown().any { f -> f.isFile }, "no files under ${it.path}") }
        assertEquals(emptyList<String>(), roots.flatMap { findings(it) })
    }

    @Test
    fun `probe - every literal in a file is a finding naming the file`(
        @TempDir dir: File,
    ) {
        forbidden.forEach { literal ->
            File(dir, "A.kt").writeText("// $literal\n")
            assertEquals(listOf("A.kt: $literal"), findings(dir), literal)
            File(dir, "A.kt").writeText("val x = \"a${literal}b\"\n")
            assertEquals(listOf("A.kt: $literal"), findings(dir), literal)
        }
        File(dir, "A.kt").writeText("val ok = 1\n")
        assertEquals(emptyList<String>(), findings(dir))
    }

    @Test
    fun `probe - a literal is case-sensitive and a missing root fails closed`(
        @TempDir dir: File,
    ) {
        File(dir, "A.kt").writeText("pint peppol gst iras now (\n")
        assertEquals(emptyList<String>(), findings(dir))
        org.junit.jupiter.api.Assertions
            .assertThrows(IllegalStateException::class.java) { findings(File(dir, "nope")) }
    }
}
