package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-Q-8125 A: no Kotlin source file of the HTTP adapter directory holds the text `UNCHECKED_CAST` or the text
 * `as ResponseEntity`; the generated interface types every response as the 200 schema and the 202 body of
 * `configureIdentityTriple` reaches the wire through a path the compiler types.
 */
class ResponseTypingTest {
    private val adapters =
        Path.of(
            "..",
            "masterdata-adapters",
            "src",
            "main",
            "kotlin",
            "com",
            "dynokore",
            "gst",
            "masterdata",
            "adapters",
        )
    private val http = adapters.resolve("http")
    private val forbidden = listOf("UNCHECKED_CAST", "as ResponseEntity")

    /** The files of [dir] (all depths) that contain one of the forbidden texts, as `file: text` lines. */
    private fun scan(dir: Path): List<String> =
        Files.walk(dir).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .toList()
                .flatMap { file ->
                    val text = Files.readString(file)
                    forbidden.filter { it in text }.map { "${file.fileName}: $it" }
                }
        }

    private fun copyOfHttp(): Path {
        val copy = Files.createTempDirectory("http-scan")
        Files.list(http).use { files -> files.forEach { Files.copy(it, copy.resolve(it.fileName)) } }
        return copy
    }

    @Test
    fun `no file of the HTTP adapter directory casts a response or suppresses an unchecked cast`() {
        val files = Files.list(http).use { it.toList() }
        assertThat(files.map { it.fileName.toString() }).contains("IdentityTriplesController.kt")
        assertThat(scan(http)).describedAs("files that cast a response").isEmpty()
    }

    @Test
    fun `probe - each forbidden text added alone to a class of the directory fails the scan naming the file`() {
        forbidden.forEach { text ->
            val copy = copyOfHttp()
            val probe = copy.resolve("ProbeController.kt")
            Files.writeString(probe, "package probe\n\nclass ProbeController {\n    // $text\n}\n")
            assertThat(scan(copy)).containsExactly("ProbeController.kt: $text")
        }
        val suppress = copyOfHttp()
        Files.writeString(suppress.resolve("ProbeSuppress.kt"), "@Suppress(\"UNCHECKED_CAST\")\nclass ProbeSuppress\n")
        assertThat(scan(suppress)).containsExactly("ProbeSuppress.kt: UNCHECKED_CAST")
        val cast = copyOfHttp()
        Files.writeString(cast.resolve("ProbeCast.kt"), "val x = y as ResponseEntity<IdentityTriple>\n")
        assertThat(scan(cast)).containsExactly("ProbeCast.kt: as ResponseEntity")
    }

    @Test
    fun `probe - the same text in the events directory is outside the scanned directory and leaves the scan passing`() {
        val events = adapters.resolve("events")
        assertThat(scan(events)).describedAs("the catalogue holds the text, so the probe is real").isNotEmpty()
        assertThat(scan(http)).isEmpty()
    }
}
