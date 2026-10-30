package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.BAD_DECLARATION
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.CLASS_COUNT_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DIGEST_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.DUPLICATE_MANIFEST_ENTRY
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.LISTED_FILE_MISSING
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MALFORMED_MANIFEST_LINE
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MANIFEST_PATH_NOT_PAYLOAD
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.MISSING_BAG_INFO_FIELD
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.OXUM_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.PAYLOAD_FILE_NOT_LISTED
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.SOURCE_READ_FAILED
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.TAG_DIGEST_MISMATCH
import com.dynokore.platform.evidencestore.exportpackage.PackageProblemCode.UNKNOWN_CONTENT_DIRECTORY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

@Suppress("LargeClass") // one test per acceptance-criterion case of INV-AC-13402, each on its own package copy
class PackageVerifierTest {
    private val dirs =
        listOf(
            "transmitted-xml",
            "acknowledgements",
            "decision-traces",
            "reconciliation-work-papers",
            "source-document-references",
            "audit-log",
        )

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T) { "expected ${T::class.simpleName} but was $value" }
        return value as T
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    /** Builds a correct package in memory with JDK code only; [payload] maps a `data/...` path to bytes. */
    private fun bag(
        payload: Map<String, ByteArray>,
        counts: Map<String, Int> = dirs.associateWith { d -> payload.keys.count { it.startsWith("data/$d/") } },
        octets: Long = payload.values.sumOf { it.size }.toLong(),
        manifestExtra: List<String> = emptyList(),
        omitLabel: String? = null,
    ): MutableMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        files["bagit.txt"] = "BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        val info =
            buildList {
                add("Package-Kind: AUDIT_PACK")
                add("Tenant: 00000000-0000-0000-0000-000000000001")
                add("Generated-At: 2026-10-30T00:00:00Z")
                add("Payload-Oxum: $octets.${payload.size}")
                dirs.forEach { add("Item-Count-$it: ${counts.getValue(it)}") }
            }.filterNot { omitLabel != null && it.startsWith("$omitLabel:") }
        files["bag-info.txt"] = (info.joinToString("\n") + "\n").toByteArray()
        files["README.txt"] = "Check with sha256sum -c manifest-sha256.txt\n".toByteArray()
        files.putAll(payload)
        val lines = payload.toSortedMap().map { (p, b) -> "${sha(b)}  $p" } + manifestExtra
        files["manifest-sha256.txt"] = (lines.joinToString("") { it + "\n" }).toByteArray()
        retag(files)
        return files
    }

    private fun retag(files: MutableMap<String, ByteArray>) {
        val tags = listOf("bagit.txt", "bag-info.txt", "README.txt", "manifest-sha256.txt")
        files["tagmanifest-sha256.txt"] = tags.joinToString("") { "${sha(files.getValue(it))}  $it\n" }.toByteArray()
    }

    private fun full(): Map<String, ByteArray> =
        dirs.associate {
            "data/$it/item-1.bin" to
                "content of $it".toByteArray()
        }

    private fun source(
        files: Map<String, ByteArray>,
        failing: String? = null,
    ) = object : PackageSource {
        override fun paths(): Set<String> = files.keys.toSet()

        override fun read(path: String): ByteArray {
            check(path != failing) { "disk error" }
            return files.getValue(path)
        }
    }

    private fun verify(
        files: Map<String, ByteArray>,
        failing: String? = null,
    ) = PackageVerifier.verify(source(files, failing))

    private fun problems(v: PackageVerification) = assertIs<PackageVerification.Invalid>(v).problems

    private fun single(
        v: PackageVerification,
        code: PackageProblemCode,
        path: String?,
    ) = assertEquals(listOf(PackageProblem(code, path)), problems(v))

    private fun editManifest(
        files: MutableMap<String, ByteArray>,
        edit: (List<String>) -> List<String>,
    ) {
        files["manifest-sha256.txt"] =
            (
                edit(
                    String(files.getValue("manifest-sha256.txt")).trimEnd('\n').split("\n"),
                ).joinToString("\n") + "\n"
            ).toByteArray()
        retag(files)
    }

    @Test
    fun `INV-AC-13402 correct package with six items is valid with counts of one`() {
        val v = assertIs<PackageVerification.Valid>(verify(bag(full())))
        assertEquals(dirs.associateWith { 1 }, v.itemCounts)
        assertEquals(6, v.payloadFiles)
        assertEquals(full().values.sumOf { it.size }.toLong(), v.payloadOctets)
    }

    @Test
    fun `INV-AC-13402 correct package with no item is valid with six zero counts`() {
        val v = assertIs<PackageVerification.Valid>(verify(bag(emptyMap())))
        assertEquals(dirs.associateWith { 0 }, v.itemCounts)
        assertEquals(0, v.payloadFiles)
    }

    @Test
    fun `INV-AC-13402 item counts come from the files not from bag-info`() {
        val payload = full() + ("data/audit-log/item-2.bin" to byteArrayOf(1))
        val v = assertIs<PackageVerification.Valid>(verify(bag(payload)))
        assertEquals(2, v.itemCounts.getValue("audit-log"))
    }

    @Test
    fun `INV-AC-13402 flipped payload byte is a digest mismatch`() {
        val f = bag(full())
        val p = "data/audit-log/item-1.bin"
        f[p] = f.getValue(p).also { it[0] = (it[0].toInt() xor 1).toByte() }
        single(verify(f), DIGEST_MISMATCH, p)
    }

    @Test
    fun `INV-AC-13402 deleted payload file is a missing listed file`() {
        val payload = full()
        val gone = "data/acknowledgements/item-1.bin"
        val f = bag(payload - gone, manifestExtra = listOf("${sha(payload.getValue(gone))}  $gone"))
        single(verify(f), LISTED_FILE_MISSING, gone)
    }

    @Test
    fun `INV-AC-13402 extra file under transmitted-xml is not listed`() {
        val extra = "data/transmitted-xml/extra.xml"
        val f = bag(full() + (extra to byteArrayOf(5)))
        editManifest(f) { lines -> lines.filterNot { it.endsWith(extra) } }
        single(verify(f), PAYLOAD_FILE_NOT_LISTED, extra)
    }

    @Test
    fun `INV-AC-13402 edited bag-info is a tag digest mismatch`() {
        val f = bag(full())
        f["bag-info.txt"] = String(f.getValue("bag-info.txt")).replace("AUDIT_PACK", "OFFBOARDING").toByteArray()
        single(verify(f), TAG_DIGEST_MISMATCH, "bag-info.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with version 0_97 is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with a byte order mark is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "\uFEFFBagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 declaration with a third line is bad`() {
        val f = bag(full())
        f["bagit.txt"] = "BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\nX: y\n".toByteArray()
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 removed Item-Count-audit-log label is a missing field`() {
        val f = bag(full(), omitLabel = "Item-Count-audit-log")
        single(verify(f), MISSING_BAG_INFO_FIELD, "Item-Count-audit-log")
    }

    @Test
    fun `INV-AC-13402 wrong octet sum is an oxum mismatch`() {
        val payload = full()
        val f = bag(payload, octets = payload.values.sumOf { it.size }.toLong() + 1)
        single(verify(f), OXUM_MISMATCH, "Payload-Oxum")
    }

    @Test
    fun `INV-AC-13402 wrong class count is a count mismatch`() {
        val f = bag(full(), counts = dirs.associateWith { 1 } + ("decision-traces" to 2))
        single(verify(f), CLASS_COUNT_MISMATCH, "Item-Count-decision-traces")
    }

    @Test
    fun `INV-AC-13402 file under data other is an unknown content directory`() {
        val payload = full() + ("data/other/x.bin" to byteArrayOf(7))
        val f = bag(payload)
        single(verify(f), UNKNOWN_CONTENT_DIRECTORY, "data/other/x.bin")
    }

    @Test
    fun `INV-AC-13402 manifest paths outside data are not payload paths`() {
        for (bad in listOf("../x", "/etc/x", "x/data/y", "data/../x", "data/./x", "data\\x")) {
            val f = bag(full(), manifestExtra = listOf("${"0".repeat(64)}  $bad"))
            single(verify(f), MANIFEST_PATH_NOT_PAYLOAD, bad)
        }
    }

    @Test
    fun `INV-AC-13402 duplicate manifest line is reported`() {
        val f = bag(full())
        editManifest(f) { it + it.first() }
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "data/acknowledgements/item-1.bin")
    }

    @Test
    fun `INV-AC-13402 short checksum is a malformed line`() {
        val f = bag(full(), manifestExtra = listOf("${"a".repeat(63)}  data/audit-log/short.bin"))
        single(verify(f), MALFORMED_MANIFEST_LINE, "manifest-sha256.txt")
    }

    @Test
    fun `INV-AC-13402 upper-case checksum of a correct file is not a problem`() {
        val f = bag(full())
        editManifest(f) { lines -> lines.map { it.substring(0, 64).uppercase() + it.substring(64) } }
        assertIs<PackageVerification.Valid>(verify(f))
    }

    @Test
    fun `INV-AC-13402 source that throws on one file is a read failure and not an exception`() {
        val p = "data/audit-log/item-1.bin"
        single(verify(bag(full()), failing = p), SOURCE_READ_FAILED, p)
    }

    @Test
    fun `INV-AC-13402 source that throws on listing is a read failure`() {
        val v =
            PackageVerifier.verify(
                object : PackageSource {
                    override fun paths(): Set<String> = error("gone")

                    override fun read(path: String): ByteArray = error("gone")
                },
            )
        single(v, SOURCE_READ_FAILED, null)
    }

    @Test
    fun `INV-AC-13402 two changes report both codes in code order`() {
        val f = bag(full())
        val p = "data/audit-log/item-1.bin"
        f[p] = f.getValue(p).also { it[0] = (it[0].toInt() xor 1).toByte() }
        f["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
        retag(f)
        assertEquals(
            listOf(PackageProblem(BAD_DECLARATION, "bagit.txt"), PackageProblem(DIGEST_MISMATCH, p)),
            problems(verify(f)),
        )
    }

    @Test
    fun `manifest path with percent-encoded line feed decodes to a real line feed in the name`() {
        val f = bag(mapOf("data/audit-log/a\nb.bin" to byteArrayOf(1, 2)))
        f["manifest-sha256.txt"] = "${sha(byteArrayOf(1, 2))}  data/audit-log/a%0Ab.bin\n".toByteArray()
        retag(f)
        assertIs<PackageVerification.Valid>(verify(f))
    }

    @Test
    fun `a missing declaration, manifest or tag manifest is reported`() {
        val f = bag(full())
        f.remove("bagit.txt")
        f.remove("manifest-sha256.txt")
        f.remove("tagmanifest-sha256.txt")
        val codes = problems(verify(f)).map { it.code }
        assertTrue(PackageProblemCode.MISSING_DECLARATION in codes)
        assertTrue(PackageProblemCode.MISSING_PAYLOAD_MANIFEST in codes)
        assertTrue(PackageProblemCode.MISSING_TAG_MANIFEST in codes)
        assertTrue(PAYLOAD_FILE_NOT_LISTED in codes)
    }

    @Test
    fun `a tag file missing from the tag manifest is reported`() {
        val f = bag(full())
        f["tagmanifest-sha256.txt"] =
            String(f.getValue("tagmanifest-sha256.txt"))
                .lines()
                .filterNot {
                    it.endsWith("README.txt")
                }.joinToString("\n")
                .toByteArray()
        single(verify(f), PackageProblemCode.TAG_FILE_NOT_LISTED, "README.txt")
    }

    private fun withEol(
        eol: String,
        vararg names: String,
    ): MutableMap<String, ByteArray> {
        val f = bag(full())
        for (n in names.filter { it != "tagmanifest-sha256.txt" }) {
            f[n] = String(f.getValue(n)).replace("\n", eol).toByteArray()
        }
        retag(f)
        if ("tagmanifest-sha256.txt" in names) {
            f["tagmanifest-sha256.txt"] = String(f.getValue("tagmanifest-sha256.txt")).replace("\n", eol).toByteArray()
        }
        return f
    }

    @Test
    fun `INV-AC-13402 CRLF and CR line ends are valid`() {
        for (eol in listOf("\r\n", "\r")) {
            assertIs<PackageVerification.Valid>(
                verify(withEol(eol, "manifest-sha256.txt", "tagmanifest-sha256.txt")),
            )
        }
        assertIs<PackageVerification.Valid>(verify(withEol("\r\n", "bagit.txt")))
        assertIs<PackageVerification.Valid>(verify(withEol("\r\n", "bag-info.txt")))
    }

    @Test
    fun `INV-AC-13402 manifest with an invalid UTF-8 byte is malformed`() {
        val f = bag(full())
        f["manifest-sha256.txt"] = f.getValue("manifest-sha256.txt") + byteArrayOf(0xC3.toByte(), 0x28, 0x0A)
        retag(f)
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt") in problems(verify(f)))
        val g = bag(full())
        g["manifest-sha256.txt"] = "\u00e9".toByteArray(Charsets.ISO_8859_1) + g.getValue("manifest-sha256.txt")
        retag(g)
        assertEquals(
            PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt"),
            problems(verify(g)).first(),
        )
    }

    @Test
    fun `INV-AC-13402 tag manifest with an invalid UTF-8 byte is malformed`() {
        val f = bag(full())
        f["tagmanifest-sha256.txt"] = byteArrayOf(0xFF.toByte()) + f.getValue("tagmanifest-sha256.txt")
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "tagmanifest-sha256.txt") in problems(verify(f)))
    }

    @Test
    fun `INV-AC-13402 declaration that is not UTF-8 is bad`() {
        val f = bag(full())
        f["bagit.txt"] = byteArrayOf(0xFF.toByte()) + f.getValue("bagit.txt")
        retag(f)
        single(verify(f), BAD_DECLARATION, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 CR in the middle of a manifest line splits it and is malformed`() {
        val f = bag(full())
        editManifest(f) { lines -> listOf(lines.first().replaceFirst("  ", "  data/x\ry")) + lines.drop(1) }
        assertTrue(PackageProblem(MALFORMED_MANIFEST_LINE, "manifest-sha256.txt") in problems(verify(f)))
    }

    @Test
    fun `INV-AC-13402 duplicate line in the tag manifest is reported with the tag file path`() {
        val f = bag(full())
        val tm = String(f.getValue("tagmanifest-sha256.txt"))
        f["tagmanifest-sha256.txt"] = (tm + tm.lines().first() + "\n").toByteArray()
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "bagit.txt")
    }

    @Test
    fun `INV-AC-13402 duplicate manifest path with a wrong first checksum is not trusted`() {
        val f = bag(full())
        editManifest(f) { lines -> listOf("0".repeat(64) + lines.first().substring(64)) + lines }
        single(verify(f), DUPLICATE_MANIFEST_ENTRY, "data/acknowledgements/item-1.bin")
    }

    @Test
    fun `INV-AC-13402 paths in two letter cases listed once each are not a duplicate`() {
        val payload = full() + ("data/audit-log/A.bin" to byteArrayOf(1)) + ("data/audit-log/a.bin" to byteArrayOf(2))
        assertIs<PackageVerification.Valid>(verify(bag(payload)))
    }

    @Test
    fun `INV-AC-13402 one to six files in the six directories give exactly those counts`() {
        val payload = linkedMapOf<String, ByteArray>()
        dirs.forEachIndexed { i, d -> repeat(i + 1) { n -> payload["data/$d/f$n.bin"] = ByteArray(n + i + 1) { 3 } } }
        val v = assertIs<PackageVerification.Valid>(verify(bag(payload)))
        assertEquals(dirs.mapIndexed { i, d -> d to i + 1 }.toMap(), v.itemCounts)
        assertEquals(21, v.payloadFiles)
        assertEquals(payload.values.sumOf { it.size }.toLong(), v.payloadOctets)
    }

    @Test
    fun `INV-AC-13402 wrong octet sum with a throwing read reports only the read failure`() {
        val payload = full()
        val p = "data/audit-log/item-1.bin"
        val f = bag(payload, octets = payload.values.sumOf { it.size }.toLong() + 5)
        single(verify(f, failing = p), SOURCE_READ_FAILED, p)
    }

    @Test
    @Suppress("LongMethod") // the table of one package per problem code
    fun `INV-AC-13402 every problem code carries the path of the defect`() {
        val ok = full()
        val p = "data/audit-log/item-1.bin"
        val cases = mutableListOf<Triple<PackageProblemCode, String?, Map<String, ByteArray>>>()
        cases += Triple(PackageProblemCode.MISSING_DECLARATION, "bagit.txt", bag(ok).also { it.remove("bagit.txt") })
        cases +=
            Triple(
                PackageProblemCode.MISSING_PAYLOAD_MANIFEST,
                "manifest-sha256.txt",
                bag(emptyMap()).also { it.remove("manifest-sha256.txt") },
            )
        cases +=
            Triple(
                PackageProblemCode.MISSING_TAG_MANIFEST,
                "tagmanifest-sha256.txt",
                bag(ok).also { it.remove("tagmanifest-sha256.txt") },
            )
        cases +=
            Triple(
                PackageProblemCode.TAG_FILE_NOT_LISTED,
                "README.txt",
                bag(ok).also { f ->
                    f["tagmanifest-sha256.txt"] =
                        String(f.getValue("tagmanifest-sha256.txt"))
                            .lines()
                            .filterNot { it.endsWith("README.txt") }
                            .joinToString("\n")
                            .toByteArray()
                },
            )
        cases +=
            Triple(
                PackageProblemCode.SOURCE_READ_FAILED,
                p,
                bag(ok),
            )
        cases +=
            Triple(
                PackageProblemCode.BAD_DECLARATION,
                "bagit.txt",
                bag(ok).also {
                    it["bagit.txt"] = "BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8\n".toByteArray()
                    retag(it)
                },
            )
        cases +=
            Triple(
                PackageProblemCode.MALFORMED_MANIFEST_LINE,
                "manifest-sha256.txt",
                bag(ok, manifestExtra = listOf("abc  data/audit-log/x")),
            )
        cases +=
            Triple(
                PackageProblemCode.MANIFEST_PATH_NOT_PAYLOAD,
                "../x",
                bag(ok, manifestExtra = listOf("${"0".repeat(64)}  ../x")),
            )
        cases +=
            Triple(
                PackageProblemCode.DUPLICATE_MANIFEST_ENTRY,
                "data/acknowledgements/item-1.bin",
                bag(ok).also { f -> editManifest(f) { it + it.first() } },
            )
        cases +=
            Triple(
                PackageProblemCode.LISTED_FILE_MISSING,
                "data/audit-log/gone.bin",
                bag(ok, manifestExtra = listOf("${"0".repeat(64)}  data/audit-log/gone.bin")),
            )
        cases +=
            Triple(
                PackageProblemCode.PAYLOAD_FILE_NOT_LISTED,
                "data/audit-log/extra.bin",
                bag(ok + ("data/audit-log/extra.bin" to byteArrayOf(1))).also { f ->
                    editManifest(f) { l -> l.filterNot { it.endsWith("extra.bin") } }
                },
            )
        cases +=
            Triple(
                PackageProblemCode.DIGEST_MISMATCH,
                p,
                bag(ok).also { it[p] = byteArrayOf(9) },
            )
        cases +=
            Triple(
                PackageProblemCode.TAG_DIGEST_MISMATCH,
                "README.txt",
                bag(ok).also { it["README.txt"] = byteArrayOf(9) },
            )
        cases +=
            Triple(
                PackageProblemCode.MISSING_BAG_INFO_FIELD,
                "Tenant",
                bag(ok, omitLabel = "Tenant"),
            )
        cases += Triple(PackageProblemCode.OXUM_MISMATCH, "Payload-Oxum", bag(ok, octets = 1))
        cases +=
            Triple(
                PackageProblemCode.CLASS_COUNT_MISMATCH,
                "Item-Count-audit-log",
                bag(ok, counts = dirs.associateWith { 1 } + ("audit-log" to 3)),
            )
        cases +=
            Triple(
                PackageProblemCode.UNKNOWN_CONTENT_DIRECTORY,
                "data/zzz/x.bin",
                bag(ok + ("data/zzz/x.bin" to byteArrayOf(1))),
            )
        assertEquals(PackageProblemCode.entries.toSet(), cases.map { it.first }.toSet())
        for ((code, path, files) in cases) {
            val failing = if (code == PackageProblemCode.SOURCE_READ_FAILED) p else null
            assertTrue(PackageProblem(code, path) in problems(verify(files, failing))) { "$code $path" }
        }
    }
}
