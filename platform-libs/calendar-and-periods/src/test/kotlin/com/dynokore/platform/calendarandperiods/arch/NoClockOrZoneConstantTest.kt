package com.dynokore.platform.calendarandperiods.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fails closed (INV-REQ-76, INV-REQ-77, INV-Q-6093 A): no file under `src/main` or `src/impl` may contain a clock, a
 * zone constant or the word for a processing time. The literals may appear under `src/test` only.
 */
class NoClockOrZoneConstantTest {
    private val forbidden =
        listOf(
            "Clock",
            "now(",
            "currentTimeMillis",
            "Asia/" + "Singapore",
            "Singa" + "pore",
            "SG" + "T",
            "process" + "ing",
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
            root
                .walkTopDown()
                .filter { it.isFile }
                .filter { f -> forbidden.any { it in f.readText() } }
                .map { it.path }
                .toList()
        }

    @Test
    fun `main and impl sources hold no clock, zone constant or processing literal`() {
        val m = moduleDir()
        val roots = listOf(File(m, "src/main"), File(m, "src/impl"))
        assertTrue(roots.all { it.isDirectory && it.walkTopDown().any { f -> f.isFile } })
        assertEquals(emptyList<String>(), offenders(roots))
    }

    @Test
    fun `probe - each forbidden literal makes the scan name the file`(
        @TempDir dir: File,
    ) {
        listOf(
            "val a = java.time.LocalDate.now()",
            "val z = \"Asia/" + "Singapore\"",
            "// process" + "ing time",
            "val c = Clock.systemUTC()",
        ).forEachIndexed { i, line ->
            File(dir, "Bad$i.kt").writeText(line)
        }
        File(dir, "Ok.kt").writeText("val x = 1")
        val found = offenders(listOf(dir)).map { File(it).name }.sorted()
        assertEquals(listOf("Bad0.kt", "Bad1.kt", "Bad2.kt", "Bad3.kt"), found)
    }

    @Test
    fun `a look-alike in a test file is not scanned because only main and impl are`(
        @TempDir dir: File,
    ) {
        File(dir, "src/main").mkdirs()
        File(dir, "src/impl").mkdirs()
        File(dir, "src/test").mkdirs()
        File(dir, "src/main/Ok.kt").writeText("val x = 1")
        File(dir, "src/test/Looks.kt").writeText("val Clocks = 1; fun nowhere() {}; val t = \"Asia/" + "Singapore\"")
        assertEquals(emptyList<String>(), offenders(listOf(File(dir, "src/main"), File(dir, "src/impl"))))
        File(dir, "src/impl/Bad.kt").writeText("val c = Clocks")
        assertEquals(
            listOf("Bad.kt"),
            offenders(listOf(File(dir, "src/main"), File(dir, "src/impl"))).map { File(it).name },
        )
    }
}
