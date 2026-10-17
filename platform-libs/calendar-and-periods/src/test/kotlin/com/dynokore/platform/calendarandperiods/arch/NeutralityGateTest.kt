package com.dynokore.platform.calendarandperiods.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Neutrality gate (INV-T-6103, fails closed): no file under `src/main` or `src/impl` may hold a time-source literal or
 * a jurisdiction or product literal (case-sensitive substring). The literals may appear under `src/test` only; they are
 * assembled from pieces here so this file is itself not a hit.
 */
class NeutralityGateTest {
    private val forbidden =
        listOf(
            "Clo" + "ck",
            "no" + "w(",
            "currentTime" + "Millis",
            "SUB-" + "005",
            "PD" + "PA",
            "G" + "ST",
            "IR" + "AS",
            "Singa" + "pore",
        )

    private fun moduleDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main").isDirectory && File(dir, "src/impl").isDirectory) return dir
            File(dir, "calendar-and-periods").takeIf { File(it, "src/main").isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("module directory not found")
    }

    private fun offenders(roots: List<File>): List<String> =
        roots.flatMap { root ->
            check(root.isDirectory) { "scan root missing: $root" }
            root
                .walkTopDown()
                .filter { it.isFile }
                .filter { f ->
                    forbidden.any { it in f.readText() }
                }.map { it.path }
                .toList()
        }

    @Test
    fun `main and impl sources hold none of the neutrality literals`() {
        val m = moduleDir()
        val roots = listOf(File(m, "src/main"), File(m, "src/impl"))
        assertTrue(roots.all { r -> r.walkTopDown().any { it.isFile } })
        assertEquals(emptyList<String>(), offenders(roots))
    }

    @Test
    fun `probe - each literal in a main or impl file makes the scan name that file`(
        @TempDir dir: File,
    ) {
        File(dir, "src/main").mkdirs()
        File(dir, "src/impl").mkdirs()
        forbidden.forEachIndexed { i, lit ->
            File(dir, "src/${if (i % 2 == 0) "main" else "impl"}/Bad$i.kt").writeText("val x = \"$lit\"")
        }
        File(dir, "src/main/Ok.kt").writeText("val x = 1")
        val found = offenders(listOf(File(dir, "src/main"), File(dir, "src/impl"))).map { File(it).name }.sorted()
        assertEquals(forbidden.indices.map { "Bad$it.kt" }.sorted(), found)
    }

    @Test
    fun `a missing scan root fails closed`(
        @TempDir dir: File,
    ) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            offenders(listOf(File(dir, "src/main")))
        }
    }
}
