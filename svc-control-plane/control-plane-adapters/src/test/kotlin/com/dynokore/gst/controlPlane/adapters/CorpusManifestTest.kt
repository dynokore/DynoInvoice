package com.dynokore.gst.controlPlane.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * The golden-document corpus of INV-REQ-2660 (INV-Q-9781 A): one JSON file per document and a manifest.json that lists
 * each file name with its SHA-256, in a fixed order. The names and the hashes are compared by equality.
 */
class CorpusManifestTest {
    private val json = JsonMapper.builder().build()

    @TempDir
    lateinit var tmp: Path

    private fun corpusDir(): Path =
        Paths.get("src/main/resources/conformance-corpus").also { check(Files.isDirectory(it)) { "missing $it" } }

    private fun copyCorpus(): Path {
        val target = Files.createDirectory(tmp.resolve("corpus"))
        Files.list(corpusDir()).use { it.forEach { f -> Files.copy(f, target.resolve(f.fileName.toString())) } }
        return target
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    private fun entries(dir: Path): List<Pair<String, String>> {
        val manifest = dir.resolve(MANIFEST)
        check(Files.isRegularFile(manifest)) { "no $MANIFEST in $dir" }
        val files = json.readTree(Files.readAllBytes(manifest)).get("files")
        return (0 until files.size()).map { files.get(it).get("name").asString() to files.get(it).get("sha256").asString() }
    }

    /** Names by equality, then each hash by equality; a failure throws. */
    private fun verify(dir: Path) {
        val entries = entries(dir)
        val names = entries.map { it.first }
        check(names.toSet().size == names.size) { "duplicate manifest entries" }
        check(names == names.sorted()) { "manifest entries are not in the fixed (sorted) order" }
        val onDisk = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().filter { it != MANIFEST }.toSet() }
        check(onDisk == names.toSet()) { "files $onDisk differ from entries ${names.toSet()}" }
        entries.forEach { (name, hash) -> check(sha256(dir.resolve(name)) == hash) { "SHA-256 of $name differs" } }
    }

    @Test
    fun `the committed corpus equals its manifest`() {
        verify(corpusDir())
    }

    @Test
    fun `every section has a document and each document names its source and expected classification`() {
        val sections = mutableSetOf<String>()
        entries(corpusDir()).forEach { (name, _) ->
            val doc = json.readTree(Files.readAllBytes(corpusDir().resolve(name)))
            doc.get("id").asString() + ".json" shouldBe name
            doc.get("source").asString().isNotBlank() shouldBe true
            doc.get("expected").asString().isNotBlank() shouldBe true
            sections += doc.get("section").asString()
        }
        sections shouldBe setOf("WORKED_EXAMPLES", "SCENARIO_MATRIX", "SCOPE_MATRIX", "CODE_MATRIX", "EDGE_CORPUS")
    }

    @Test
    fun `one file added without an entry fails`() {
        val dir = copyCorpus()
        Files.writeString(dir.resolve("extra.json"), "{}")
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    @Test
    fun `one entry without a file fails`() {
        val dir = copyCorpus()
        Files.delete(dir.resolve(entries(dir).first().first))
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    @Test
    fun `one byte of a document changed fails`() {
        val dir = copyCorpus()
        val file = dir.resolve(entries(dir).first().first)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(file, bytes)
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    @Test
    fun `the look-alike name Manifest json in place of manifest json fails`() {
        val dir = copyCorpus()
        Files.move(dir.resolve(MANIFEST), dir.resolve("Manifest.json"))
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    @Test
    fun `entries out of the fixed order fail`() {
        val dir = copyCorpus()
        val manifest = dir.resolve(MANIFEST)
        val tree = json.readTree(Files.readAllBytes(manifest))
        val files = tree.get("files")
        val reversed = (0 until files.size()).map { files.get(it) }.reversed()
        Files.writeString(manifest, json.writeValueAsString(mapOf("files" to reversed)))
        shouldThrow<IllegalStateException> { verify(dir) }
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
