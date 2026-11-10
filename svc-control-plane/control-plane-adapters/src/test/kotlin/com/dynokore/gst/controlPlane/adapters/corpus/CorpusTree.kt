package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/** Helpers that copy and edit the committed corpus tree; every probe edits a copy, never the committed files. */
class CorpusTree(
    private val tmp: Path,
) {
    val json: JsonMapper = JsonMapper.builder().build()

    val committed: Path =
        Paths.get("src/main/resources/conformance-corpus").also { check(Files.isDirectory(it)) { "missing $it" } }

    /** A fresh copy of the committed tree. */
    fun copy(): Path {
        val target = Files.createTempDirectory(tmp, "corpus")
        Files.list(committed).use { it.forEach { f -> Files.copy(f, target.resolve(f.fileName.toString())) } }
        return target
    }

    fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") {
            String.format(Locale.ROOT, "%02x", it)
        }

    /** Rewrites manifest.json to list exactly the files of [dir], so a probe tests the content, not the hash. */
    fun rehash(dir: Path) {
        val names =
            Files
                .list(dir)
                .use { s ->
                    s.map { it.fileName.toString() }.toList()
                }.filter { it != "manifest.json" }
                .sorted()
        val files = names.map { mapOf("name" to it, "sha256" to sha256(dir.resolve(it))) }
        Files.writeString(dir.resolve("manifest.json"), json.writeValueAsString(mapOf("files" to files)))
    }

    fun edit(
        dir: Path,
        name: String,
        change: (String) -> String,
    ) {
        val file = dir.resolve(name)
        val before = Files.readString(file)
        val after = change(before)
        check(after != before) { "the probe edit changed nothing in $name" }
        Files.writeString(file, after)
        rehash(dir)
    }

    fun read(name: String): JsonNode = json.readTree(Files.readAllBytes(committed.resolve(name)))

    fun text(name: String): String = Files.readString(committed.resolve(name))

    fun manifestNames(): List<String> =
        read("manifest.json").get("files").let { f -> (0 until f.size()).map { f.get(it).get("name").asString() } }

    fun corpus(dir: Path = committed) = InProcessConformanceCorpus(DirectoryCorpusSource(dir))

    fun pack(
        type: String,
        contents: String?,
        version: String = "2026.1",
    ): RegulatoryPack =
        RegulatoryPack.draft(
            PackType(type),
            PackVersion(version),
            listOf("IRAS e-Tax Guide 2026-08"),
            ActorId("alice"),
            LocalDate.parse("2026-10-01"),
            contents?.let { PackContents(it) },
        )

    /** The classification of every golden document under [pack], keyed by document id. */
    fun classifyAll(
        corpus: InProcessConformanceCorpus,
        pack: RegulatoryPack,
    ): Map<String, String?> = corpus.documents().associate { it.id.value to corpus.classify(pack, it) }
}
