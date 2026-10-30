package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.ContentHash
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.TreeMap

/**
 * The files of a package: relative path to bytes, listed in ascending order of the path string. The map is
 * immutable and every read returns a copy of the bytes.
 */
class PackageFiles(
    files: Map<String, ByteArray>,
) {
    private val content: Map<String, ByteArray> =
        Collections.unmodifiableMap(
            TreeMap<String, ByteArray>().also { sorted ->
                files.forEach { (k, v) ->
                    sorted[k] =
                        v.copyOf()
                }
            },
        )

    /** The relative paths in ascending order of the path string. */
    val paths: List<String> = content.keys.toList()

    /** A copy of the bytes at [path], or null when there is no such file. */
    fun bytes(path: String): ByteArray? = content[path]?.copyOf()
}

/**
 * Writes a self-describing BagIt 1.0 package (RFC 8493) of evidence items as pure code: it decides nothing
 * about access and reads no clock (12-factor: the instant is an argument). The digest is SHA-256 (NIST SP
 * 800-53 SI-7), in the form `sha256sum -c` reads.
 */
object AuditPackWriter {
    private const val BAGIT = "bagit.txt"
    private const val BAG_INFO = "bag-info.txt"
    private const val README = "README.txt"
    private const val MANIFEST = "manifest-sha256.txt"
    private const val TAG_MANIFEST = "tagmanifest-sha256.txt"

    fun write(
        scope: PackageScope,
        items: List<PackageItem>,
        generatedAt: Instant,
    ): PackageFiles {
        val payload = TreeMap<String, ByteArray>()
        for (item in items) {
            val path = "data/${item.contentClass.directory}/${item.name}"
            require(payload.put(path, item.bytes()) == null) {
                "duplicate item ${item.contentClass} '${item.name}'"
            }
        }
        val manifest = payload.map { (path, bytes) -> "${ContentHash.of(bytes).hex}  $path" }
        val bagInfo = bagInfo(scope, items, payload, generatedAt)

        val tagged =
            linkedMapOf(
                BAGIT to text(listOf("BagIt-Version: 1.0", "Tag-File-Character-Encoding: UTF-8")),
                BAG_INFO to text(bagInfo),
                README to text(readme()),
                MANIFEST to text(manifest),
            )
        val tagManifest = tagged.map { (path, bytes) -> "${ContentHash.of(bytes).hex}  $path" }
        val all = TreeMap<String, ByteArray>(payload)
        all.putAll(tagged)
        all[TAG_MANIFEST] = text(tagManifest)
        return PackageFiles(all)
    }

    private fun bagInfo(
        scope: PackageScope,
        items: List<PackageItem>,
        payload: Map<String, ByteArray>,
        generatedAt: Instant,
    ): List<String> {
        val lines = mutableListOf<String>()

        fun label(
            name: String,
            value: String,
        ) {
            require('\n' !in value && '\r' !in value) { "bag-info value of $name must not contain CR or LF" }
            lines += "$name: $value"
        }
        when (scope) {
            is PackageScope.EntityPeriod -> {
                label("Package-Kind", "AUDIT_PACK")
                label("Tenant", scope.tenant.value.toString())
                label("Entity", scope.entity.value)
                label("Period", scope.period.value)
            }

            is PackageScope.WholeTenant -> {
                label("Package-Kind", "OFFBOARDING")
                label("Tenant", scope.tenant.value.toString())
            }
        }
        label("Generated-At", generatedAt.toString())
        label("Bagging-Date", generatedAt.atZone(ZoneOffset.UTC).toLocalDate().toString())
        label("Payload-Oxum", "${payload.values.sumOf { it.size.toLong() }}.${items.size}")
        for (cls in PackageContentClass.entries) {
            label("Item-Count-${cls.directory}", items.count { it.contentClass == cls }.toString())
        }
        return lines
    }

    private fun readme(): List<String> =
        listOf(
            "This directory is a BagIt 1.0 package (RFC 8493) of evidence items.",
            "",
            "Content is under data/, one directory per content class:",
        ) +
            PackageContentClass.entries.map { "  data/${it.directory}/" } +
            listOf(
                "",
                "bag-info.txt states the scope, the time, the total size (Payload-Oxum)",
                "and the item count of each class.",
                "manifest-sha256.txt lists the SHA-256 of every file under data/;",
                "tagmanifest-sha256.txt lists the SHA-256 of the other description files.",
                "",
                "To check the content, run this from the package root:",
                "  sha256sum -c manifest-sha256.txt",
            )

    private fun text(lines: List<String>): ByteArray =
        lines.joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8)
}
