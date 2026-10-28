package com.dynokore.gst.evidence.application.cases

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/** The owner's rule of 2026-10-25: a case decision names a permission, never a role. */
class CaseSourceScanTest {
    private val role = "security.officer"
    private val dirs =
        listOf(
            "evidence-domain/src/main/kotlin/com/dynokore/gst/evidence/domain/cases",
            "evidence-application/src/main/kotlin/com/dynokore/gst/evidence/application/cases",
        )

    private fun root(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("ac-keys.tsv"))) dir = dir.parent
        return requireNotNull(dir) { "repository root not found" }
    }

    private fun filesOf(dir: Path): List<Path> = Files.walk(dir).use { s -> s.filter { it.extension == "kt" }.toList() }

    @Test
    fun `INV-AC-11696 no case source file names the role`() {
        val root = root()
        val files =
            dirs.flatMap {
                val dir = root.resolve(it)
                check(dir.isDirectory()) { "missing $it" }
                filesOf(dir)
            }
        files.shouldNotBeEmpty()
        files.filter { it.readText().contains(role) }.shouldBeEmpty()
    }
}
