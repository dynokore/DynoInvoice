package com.dynokore.platform.evidencestore.exportpackage

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Verifies a BagIt 1.0 package (RFC 8493 section 3) without the platform: complete, checksums, sizes and the six
 * class counts (INV-REQ-1347, INV-AC-13402). Fail-safe defaults (NIST SP 800-53 SI-7): every rule runs, every problem
 * is collected, a read error is a problem and never a pass, and [PackageVerification.Valid] means no problem at all.
 */
object PackageVerifier {
    private const val DECLARATION = "bagit.txt"
    private const val BAG_INFO = "bag-info.txt"
    private const val README = "README.txt"
    private const val MANIFEST = "manifest-sha256.txt"
    private const val TAG_MANIFEST = "tagmanifest-sha256.txt"
    private const val PAYLOAD_ROOT = "data"
    private const val OXUM = "Payload-Oxum"
    private const val COUNT_LABEL = "Item-Count-"
    private const val SHA_256_HEX = 64
    private const val MIN_PAYLOAD_SEGMENTS = 3
    private val DIRECTORIES =
        listOf(
            "transmitted-xml",
            "acknowledgements",
            "decision-traces",
            "reconciliation-work-papers",
            "source-document-references",
            "audit-log",
        )
    private val REQUIRED_LABELS =
        listOf("Package-Kind", "Tenant", "Generated-At", OXUM) + DIRECTORIES.map { COUNT_LABEL + it }
    private val TAG_FILES = listOf(DECLARATION, BAG_INFO, README, MANIFEST)
    private val EXPECTED_DECLARATION = listOf("BagIt-Version: 1.0", "Tag-File-Character-Encoding: UTF-8")
    private val MANIFEST_LINE = Regex("^([0-9A-Fa-f]{$SHA_256_HEX})[ \t]+(.+)$")
    private val LINE_END = Regex("\r\n|\r|\n")
    private val PERCENT = Regex("%(0[AaDd]|25)")

    fun verify(source: PackageSource): PackageVerification {
        val files = Files(source)
        val paths = files.listing()
        val payload = paths.filter { it.startsWith("$PAYLOAD_ROOT/") }.sorted()
        var octets: Long? = null
        if (files.listed) {
            checkDeclaration(files)
            checkPayload(
                files,
                payload,
                readManifest(files, MANIFEST, PackageProblemCode.MISSING_PAYLOAD_MANIFEST, payloadOnly = true),
            )
            checkTags(files)
            octets = payloadOctets(files, payload)
            checkBagInfo(files, payload, octets)
        }
        val problems =
            files.problems.sortedWith(compareBy<PackageProblem> { it.code.ordinal }.thenBy(nullsFirst()) { it.path })
        return if (problems.isEmpty()) {
            PackageVerification.Valid(payload.size, octets ?: 0L, counts(payload))
        } else {
            PackageVerification.Invalid(problems)
        }
    }

    private fun counts(payload: List<String>): Map<String, Int> =
        DIRECTORIES.associateWith { dir -> payload.count { it.startsWith("$PAYLOAD_ROOT/$dir/") } }

    /** The source behind a problem collector: reads are cached, and any failure of the source becomes a problem. */
    private class Files(
        private val source: PackageSource,
    ) {
        val problems = mutableSetOf<PackageProblem>()
        private var paths: Set<String> = emptySet()
        private val cache = mutableMapOf<String, ByteArray?>()

        fun add(
            code: PackageProblemCode,
            path: String?,
        ) {
            problems.add(PackageProblem(code, path))
        }

        fun has(path: String): Boolean = path in paths

        /** False when the source could not even list its files (recorded as one problem without a path). */
        var listed = false
            private set

        fun listing(): Set<String> {
            paths = guarded(null) { source.paths().toSet() }.also { listed = it != null } ?: emptySet()
            return paths
        }

        /** Bytes of a present file; null when it is absent or its read failed (the failure is recorded). */
        fun read(path: String): ByteArray? =
            if (has(path)) cache.getOrPut(path) { guarded(path) { source.read(path) } } else null

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // record has no cause field; CS-50
        private fun <T> guarded(
            path: String?,
            block: () -> T,
        ): T? =
            try {
                block()
            } catch (e: Exception) {
                add(PackageProblemCode.SOURCE_READ_FAILED, path)
                null
            }
    }

    /** Pure text helpers of the format. */
    private object Format {
        fun text(bytes: ByteArray): String? =
            runCatching {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()

        /** Lines end in LF, CR or CRLF (RFC 8493 section 2.3); one empty last element is dropped. */
        fun lines(text: String): List<String> =
            LINE_END.split(text).let { if (it.last().isEmpty()) it.dropLast(1) else it }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }

        fun decode(path: String): String =
            PERCENT.replace(path) {
                when (it.groupValues[1].uppercase()) {
                    "0A" -> "\n"
                    "0D" -> "\r"
                    else -> "%"
                }
            }

        fun isPayloadPath(path: String): Boolean =
            path.startsWith("$PAYLOAD_ROOT/") && !path.contains('\\') &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun checkDeclaration(files: Files) {
        if (!files.has(DECLARATION)) {
            files.add(PackageProblemCode.MISSING_DECLARATION, DECLARATION)
            return
        }
        val text = files.read(DECLARATION)?.let { Format.text(it) }
        val read = files.problems.none { it.code == PackageProblemCode.SOURCE_READ_FAILED && it.path == DECLARATION }
        if (read &&
            (text == null || Format.lines(text) != EXPECTED_DECLARATION)
        ) {
            files.add(PackageProblemCode.BAD_DECLARATION, DECLARATION)
        }
    }

    /**
     * The accepted `path` to lower-case checksum map of a manifest file (a duplicated path maps to
     * null: no checksum of it is trusted). With [payloadOnly] the paths must be payload paths (rule 2); a tag manifest
     * allows any path.
     */
    private fun readManifest(
        files: Files,
        file: String,
        missing: PackageProblemCode,
        payloadOnly: Boolean,
    ): Map<String, String?> {
        if (!files.has(file)) files.add(missing, file)
        val bytes = files.read(file) ?: return emptyMap()
        val text = Format.text(bytes)
        if (text == null) files.add(PackageProblemCode.MALFORMED_MANIFEST_LINE, file)
        val accepted = linkedMapOf<String, String?>()
        val matches =
            (
                if (text.isNullOrEmpty()) {
                    emptyList()
                } else {
                    Format.lines(
                        text,
                    )
                }
            ).map { MANIFEST_LINE.matchEntire(it) }
        if (matches.any { it == null }) files.add(PackageProblemCode.MALFORMED_MANIFEST_LINE, file)
        for (match in matches.filterNotNull()) {
            val path = Format.decode(match.groupValues[2])
            when {
                payloadOnly &&
                    !Format.isPayloadPath(
                        path,
                    )
                -> {
                    files.add(PackageProblemCode.MANIFEST_PATH_NOT_PAYLOAD, path)
                }

                path in accepted -> {
                    files.add(PackageProblemCode.DUPLICATE_MANIFEST_ENTRY, path)
                    accepted[path] = null
                }

                else -> {
                    accepted[path] = match.groupValues[1].lowercase()
                }
            }
        }
        return accepted
    }

    /** Completeness and checksums (3), and the directory rule of (5). */
    private fun checkPayload(
        files: Files,
        payload: List<String>,
        digests: Map<String, String?>,
    ) {
        for (path in digests.keys) {
            if (!files.has(path)) files.add(PackageProblemCode.LISTED_FILE_MISSING, path)
        }
        for (path in payload) {
            val expected = digests[path]
            if (path !in digests) files.add(PackageProblemCode.PAYLOAD_FILE_NOT_LISTED, path)
            val bytes = files.read(path)
            if (expected != null && bytes != null &&
                Format.sha256(bytes) != expected
            ) {
                files.add(PackageProblemCode.DIGEST_MISMATCH, path)
            }
            val segments = path.split('/')
            if (segments.size < MIN_PAYLOAD_SEGMENTS || segments[1] !in DIRECTORIES) {
                files.add(PackageProblemCode.UNKNOWN_CONTENT_DIRECTORY, path)
            }
        }
    }

    /** Sum of payload file sizes; null when one could not be read, since the sum is then unknown. */
    private fun payloadOctets(
        files: Files,
        payload: List<String>,
    ): Long? {
        val sizes = payload.map { files.read(it)?.size }
        return if (sizes.any { it == null }) null else sizes.sumOf { it!!.toLong() }
    }

    private fun checkTags(files: Files) {
        val listed = readManifest(files, TAG_MANIFEST, PackageProblemCode.MISSING_TAG_MANIFEST, payloadOnly = false)
        if (!files.has(TAG_MANIFEST)) return
        for (file in TAG_FILES) {
            val expected = listed[file]
            val bytes = files.read(file)
            when {
                file in listed && expected == null -> Unit

                // duplicate, already reported

                file !in listed -> files.add(PackageProblemCode.TAG_FILE_NOT_LISTED, file)

                !files.has(file) -> files.add(PackageProblemCode.TAG_DIGEST_MISMATCH, file)

                bytes != null && Format.sha256(
                    bytes,
                ) != expected -> files.add(PackageProblemCode.TAG_DIGEST_MISMATCH, file)
            }
        }
    }

    /** `Label: value` lines; the first occurrence of a label wins. */
    private fun bagInfoFields(text: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (line in Format.lines(text)) {
            val at = line.indexOf(':')
            if (at > 0) fields.putIfAbsent(line.substring(0, at), line.substring(at + 1).trim())
        }
        return fields
    }

    private fun checkBagInfo(
        files: Files,
        payload: List<String>,
        octets: Long?,
    ) {
        val bytes = files.read(BAG_INFO)
        if (bytes == null && files.has(BAG_INFO)) return // read failure already recorded
        val fields = bagInfoFields(bytes?.let { Format.text(it) }.orEmpty())
        for (label in REQUIRED_LABELS.filter { it !in fields }) {
            files.add(
                PackageProblemCode.MISSING_BAG_INFO_FIELD,
                label,
            )
        }
        if (octets != null && OXUM in fields && fields[OXUM] != "$octets.${payload.size}") {
            files.add(PackageProblemCode.OXUM_MISMATCH, OXUM)
        }
        for ((dir, count) in counts(payload)) {
            val label = COUNT_LABEL + dir
            if (label in fields &&
                fields[label] != count.toString()
            ) {
                files.add(PackageProblemCode.CLASS_COUNT_MISMATCH, label)
            }
        }
    }
}
