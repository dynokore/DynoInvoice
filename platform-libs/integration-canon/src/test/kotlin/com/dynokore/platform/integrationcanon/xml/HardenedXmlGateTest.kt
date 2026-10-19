package com.dynokore.platform.integrationcanon.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Construction gate (CS-25; INV-Q-7068): no file under `src/main` or `src/impl` builds an XML factory except
 * `HardenedXml.kt`, matched by its path relative to the repository root (equality). Fails closed: a missing root
 * or an empty scan is a failure.
 */
class HardenedXmlGateTest {
    private val allowed = "integration-canon/src/impl/kotlin/com/dynokore/platform/integrationcanon/xml/HardenedXml.kt"
    private val construction =
        Regex(
            "(DocumentBuilderFactory\\s*\\.\\s*newInstance|SAXParserFactory\\s*\\.\\s*newInstance|" +
                "XMLInputFactory\\s*\\.\\s*newInstance|XMLInputFactory\\s*\\.\\s*newFactory|" +
                "TransformerFactory\\s*\\.\\s*newInstance|SchemaFactory\\s*\\.\\s*newInstance)",
        )

    private fun repoRoot(start: File = File("").absoluteFile): File {
        var dir: File? = start
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private fun findings(
        root: File,
        scanned: List<String>,
    ): List<String> {
        check(root.isDirectory) { "scan root ${root.path} is missing" }
        val files =
            scanned.flatMap { File(root, it).walkTopDown().filter { f -> f.isFile }.toList() }
        check(files.isNotEmpty()) { "scan found no files" }
        return files
            .filter { construction.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != allowed }
    }

    @Test
    fun `only HardenedXml constructs XML factories`() {
        val root = repoRoot()
        val scanned = listOf("integration-canon/src/main", "integration-canon/src/impl")
        assertEquals(emptyList<String>(), findings(root, scanned))
        assertTrue(
            construction.containsMatchIn(File(root, allowed).readText()),
            "the allowed file builds the factories",
        )
    }

    private fun write(
        dir: File,
        path: String,
        text: String,
    ) {
        File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)
    }

    @Test
    fun `probe - the call in another file is a finding`(
        @TempDir dir: File,
    ) {
        write(dir, "m/src/impl/Other.kt", "val f = DocumentBuilderFactory.newInstance()\n")
        assertEquals(listOf("m/src/impl/Other.kt"), findings(dir, listOf("m/src/impl")))
    }

    @Test
    fun `probe - HardenedXml dot kt under a different package path is a finding`(
        @TempDir dir: File,
    ) {
        val path = "integration-canon/src/impl/kotlin/com/dynokore/platform/integrationcanon/xml2/HardenedXml.kt"
        write(dir, path, "val f = SAXParserFactory.newInstance()\n")
        assertEquals(listOf(path), findings(dir, listOf("integration-canon/src/impl")))
    }

    @Test
    fun `probe - the allowed path itself is not a finding`(
        @TempDir dir: File,
    ) {
        write(dir, allowed, "val f = XMLInputFactory.newFactory()\n")
        assertEquals(emptyList<String>(), findings(dir, listOf("integration-canon/src/impl")))
    }

    @Test
    fun `probe - the call split over two lines is a finding`(
        @TempDir dir: File,
    ) {
        write(dir, "m/src/main/Other.kt", "val f = DocumentBuilderFactory\n    .newInstance()\n")
        assertEquals(listOf("m/src/main/Other.kt"), findings(dir, listOf("m/src/main")))
    }

    @Test
    fun `probe - each of the six calls is a finding`(
        @TempDir dir: File,
    ) {
        listOf(
            "DocumentBuilderFactory.newInstance",
            "SAXParserFactory.newInstance",
            "XMLInputFactory.newInstance",
            "XMLInputFactory.newFactory",
            "TransformerFactory.newInstance",
            "SchemaFactory.newInstance",
        ).forEach {
            write(dir, "m/src/main/Other.kt", "val f = $it()\n")
            assertEquals(listOf("m/src/main/Other.kt"), findings(dir, listOf("m/src/main")), it)
        }
    }

    @Test
    fun `probe - a missing root throws and an empty scan fails`(
        @TempDir dir: File,
    ) {
        assertThrows(IllegalStateException::class.java) { findings(File(dir, "nope"), listOf("a")) }
        assertThrows(IllegalStateException::class.java) { findings(dir, listOf("empty")) }
    }
}
