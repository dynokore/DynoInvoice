package com.dynokore.gst.rules.adapters.packs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream

/**
 * The initial content of the Schematron pack is the Schematron files of the PINT-SG v1.4.1 `resources.zip`
 * (INV-REQ-482).
 * The zip is the committed download, pinned by SHA-256 and verified before any entry is read (SLSA).
 */
class PintSgInitialContentTest {
    private companion object {
        const val ZIP_SHA256 = "584353b4fed58474d3ec7e32cc99d24eb8f50c812ac7d4a4ae72e6e0566b2670"
        const val ZIP_SIZE = 1140845
        val PINNED =
            setOf(
                "PINT-UBL-validation-preprocessed.sch" to
                    "4e66ec0e7659e82ca98f3b57f653583fba4697c99e9b79d29ce7c46aa81ed010",
                "PINT-UBL-validation-preprocessed.xslt" to
                    "1b239af213b29f1127099bc0af7335689cfe45c6e6b11144f1316c52a0fed381",
                "PINT-jurisdiction-aligned-rules.sch" to
                    "3f93031bd2e578bd1015a7a5b4f978225dceec60118d5392b1a12dab818a8ecc",
                "PINT-jurisdiction-aligned-rules.xslt" to
                    "cb64ecfccad47b0c69c9be060d7c20ac084624860ff7a01e986f3b52b01289a0",
            )
        private val NAME = Regex("[A-Za-z0-9._-]+")
        private val DIRECTORIES = listOf("trn-invoice/schematron/", "trn-creditnote/schematron/")

        fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun resource(path: String): ByteArray =
            PintSgInitialContentTest::class.java.getResourceAsStream(path)!!.use { it.readBytes() }

        /** The name of an entry `<dir>/<name>` with a lower-case `.sch` or `.xslt` suffix; else null. */
        fun schematronName(path: String): String? {
            val dir = DIRECTORIES.firstOrNull { path.startsWith(it) } ?: return null
            val name = path.removePrefix(dir)
            return name.takeIf { NAME.matches(it) && (it.endsWith(".sch") || it.endsWith(".xslt")) }
        }

        /** Verifies the zip pin first, then reads the entries; a mismatch throws before any entry is read. */
        fun verifiedEntries(
            zip: ByteArray,
            digestFile: String,
        ): Map<String, ByteArray> {
            check(sha(zip) == ZIP_SHA256) { "resources.zip digest differs from the pinned digest" }
            check(zip.size == ZIP_SIZE) { "resources.zip size differs from the pinned size" }
            check(digestFile.trim().split(Regex("\\s+")).first() == ZIP_SHA256) { "digest file differs from the pin" }
            val out = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                generateSequence { zis.nextEntry }.forEach { if (!it.isDirectory) out[it.name] = zis.readBytes() }
            }
            return out
        }

        /** name to bytes of the Schematron files; the copies of one name must be identical (fail closed). */
        fun selected(entries: Map<String, ByteArray>): Map<String, ByteArray> {
            val out = linkedMapOf<String, ByteArray>()
            for ((path, bytes) in entries) {
                val name = schematronName(path) ?: continue
                val earlier = out[name]
                check(earlier == null || sha(earlier) == sha(bytes)) { "'$name' has two different digests" }
                out[name] = bytes
            }
            return out
        }
    }

    private val zip = resource("/pint-sg/resources.zip")
    private val digestFile = String(resource("/pint-sg/resources.zip.sha256"))
    private val seedBytes = resource("/packs/pint-sg/schematron-pack-contents.json")

    @Test
    fun `INV-AC-7965 the zip has the pinned digest and size`() {
        sha(zip) shouldBe ZIP_SHA256
        zip.size shouldBe ZIP_SIZE
        digestFile shouldBe "$ZIP_SHA256  resources.zip\n"
    }

    @Test
    fun `INV-AC-7965 the pairs of the seed equal the pairs of the zip`() {
        val fromZip = selected(verifiedEntries(zip, digestFile)).map { (n, b) -> n to sha(b) }.toSet()
        fromZip shouldBe PINNED
        val seed = SchematronPackContents.decode(seedBytes)
        seed.version shouldBe "1.4.1"
        seed.files.size shouldBe PINNED.size
        seed.files.map { it.name to sha(it.content) }.toSet() shouldBe fromZip
        seed.files.map { it.name } shouldBe PINNED.map { it.first }
    }

    @Test
    fun `INV-AC-1670 the seed holds exactly the Schematron files of the zip byte for byte`() {
        val chosen = selected(verifiedEntries(zip, digestFile))
        val seed = SchematronPackContents.decode(seedBytes)
        seed.files.map { it.name }.toSet() shouldBe chosen.keys
        seed.files.forEach { assertArrayEquals(chosen.getValue(it.name), it.content, it.name) }
    }

    @Test
    fun `INV-AC-1670 the seed holds no code list, example, PDF or nested zip`() {
        val seed = SchematronPackContents.decode(seedBytes)
        seed.files.forEach { f ->
            (f.name.endsWith(".sch") || f.name.endsWith(".xslt")) shouldBe true
            listOf(".gc", ".xml", ".pdf", ".zip").none { f.name.endsWith(it) } shouldBe true
        }
    }

    @Test
    fun `selection takes only the exact Schematron paths`() {
        val n = "PINT-jurisdiction-aligned-rules"
        schematronName("trn-invoice/schematron/$n.sch") shouldBe "$n.sch"
        schematronName("trn-creditnote/schematron/$n.xslt") shouldBe "$n.xslt"
        listOf(
            "trn-invoice/schematron/sub/$n.sch",
            "trn-invoice/codelist/$n.sch",
            "trn-invoice/schematron/$n.SCH",
            "trn-invoice/schematron/$n.xslt.txt",
            "x/trn-invoice/schematron/$n.sch",
            "trn-invoice/schematron/../$n.sch",
        ).forEach { schematronName(it) shouldBe null }
    }

    @Test
    fun `a name with two different digests fails closed and a changed zip fails before any entry is read`() {
        val a = "trn-invoice/schematron/a.sch" to "x".toByteArray()
        val b = "trn-creditnote/schematron/a.sch" to "y".toByteArray()
        runCatching { selected(mapOf(a, b)) }.isFailure shouldBe true
        val changed = zip.copyOf().also { it[100] = (it[100] + 1).toByte() }
        runCatching { verifiedEntries(changed, digestFile) }.isFailure shouldBe true
    }
}
