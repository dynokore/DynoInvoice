package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Counts the narrow `@Suppress("DEPRECATION")` markers that let the existing callers of the deprecated
 * actor-less `TenantContext.bind(tenant, block)` and `PlatformTenantBinding.bindPlatform(block)` compile
 * under warnings-as-errors (CS-01; coordinator ruling on INV-T-3980, INV-Q-3997). Each one sits on the
 * enclosing function or statement, directly under the line `// INV-T-3999 removes this call`, never file-
 * or module-wide.
 *
 * Callers move to the actor-bound forms in INV-T-3979, INV-T-3981 and INV-T-3983, each lowering
 * [EXPECTED]; INV-T-3999 deletes the actor-less forms and asserts [EXPECTED] is zero.
 */
class ActorlessBindingSuppressionsTest {
    private val repository: File = File("..").canonicalFile

    @Test
    fun `every suppression is narrow, marked, and counted`() {
        assertTrue(File(repository, "settings.gradle.kts").isFile, "run from the tenancy-kernel project directory")
        val sources =
            repository
                .walkTopDown()
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        val markers = mutableListOf<String>()
        for (file in sources) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val at = "${file.relativeTo(repository)}:${index + 1}"
                val fileWide = line.trimStart().startsWith("@file:Suppress") && "DEPRECATION" in line
                assertTrue(!fileWide, "file-wide suppression at $at")
                if (line.trim() == MARKER) {
                    val next = lines.getOrNull(index + 1)?.trim()
                    assertEquals(SUPPRESSION, next, "the marker at $at must sit on the suppression")
                    markers += at
                }
            }
        }
        assertEquals(EXPECTED, markers.size, "suppressions of the actor-less binding: $markers")
    }

    private companion object {
        const val MARKER = "// INV-T-3999 removes this call"
        const val SUPPRESSION = "@Suppress(\"DEPRECATION\")"

        /** Lowered by each caller-migration task; INV-T-3999 sets it to zero. */
        const val EXPECTED = 23
    }
}
