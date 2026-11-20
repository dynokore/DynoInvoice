package com.dynokore.gst.reconciliation.adapters.export

import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class ExportFontsTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var shared: Path
    }

    private val latin = "fonts/NotoSans-Regular.ttf"
    private val tamil = "fonts/NotoSansTamil-Regular.ttf"
    private val notices = listOf("NotoSans-OFL.txt", "NotoSansTamil-OFL.txt")
    private val licenceText = "SIL OPEN FONT LICENSE Version 1.1"

    /** The classpath resources under `fonts`, materialised once (they sit in a jar on the test classpath). */
    private fun resourceDir(): Path {
        val dir = shared.resolve("real")
        if (Files.exists(dir)) return dir
        Files.createDirectories(dir)
        val uri =
            ExportFonts::class.java.classLoader
                .getResource(ExportFonts.RECORD)!!
                .toURI()
        val opened = if (uri.scheme == "jar") FileSystems.newFileSystem(uri, emptyMap<String, Any>()) else null
        try {
            val source = Path.of(uri).parent
            Files.list(source).use { s -> s.forEach { Files.copy(it, dir.resolve(it.fileName.toString())) } }
        } finally {
            opened?.close()
        }
        return dir
    }

    private fun copy(into: Path): Path {
        val target = into.resolve("fonts")
        Files.createDirectories(target)
        Files.list(resourceDir()).use { s -> s.forEach { Files.copy(it, target.resolve(it.fileName.toString())) } }
        return target
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun flipByte(file: Path) {
        val b = Files.readAllBytes(file)
        b[b.size / 2] = (b[b.size / 2] + 1).toByte()
        Files.write(file, b)
    }

    private fun rewriteRecord(
        dir: Path,
        f: (List<String>) -> List<String>,
    ) {
        val tsv = dir.resolve("fonts.tsv")
        Files.writeString(tsv, f(Files.readAllLines(tsv)).joinToString("\n", postfix = "\n"))
    }

    /** The family name (name ID 1, Windows Unicode record) read from the `name` table of an sfnt file. */
    private fun familyOf(font: ByteArray): String {
        val b = ByteBuffer.wrap(font)
        val tables = b.getShort(4).toInt()
        val nameAt =
            (0 until tables)
                .map { 12 + 16 * it }
                .first { String(font, it, 4, Charsets.US_ASCII) == "name" }
                .let { b.getInt(it + 8) }
        val count = b.getShort(nameAt + 2).toInt()
        val strings = nameAt + (b.getShort(nameAt + 4).toInt() and 0xFFFF)
        for (i in 0 until count) {
            val r = nameAt + 6 + 12 * i
            if (b.getShort(r).toInt() == 3 && b.getShort(r + 6).toInt() == 1) {
                val len = b.getShort(r + 8).toInt() and 0xFFFF
                val off = b.getShort(r + 10).toInt() and 0xFFFF
                return String(font, strings + off, len, Charsets.UTF_16BE)
            }
        }
        error("no family name")
    }

    /** Every problem of a fonts directory, each naming its file; empty means the record is complete. */
    private fun problems(dir: Path): List<String> {
        val out = mutableListOf<String>()
        val fonts = ExportFonts(dir)
        val rows =
            try {
                fonts.records()
            } catch (e: IllegalStateException) {
                return listOf(e.message!!)
            }
        rows.forEach { r ->
            try {
                val bytes = fonts.fontBytes(r.file)
                val family = familyOf(bytes)
                if (family != r.family) out += "${r.file}: name table family $family differs from ${r.family}"
            } catch (e: IllegalStateException) {
                out += e.message!!
            }
        }
        val known = rows.map { it.file.removePrefix("fonts/") } + notices + "fonts.tsv"
        Files.list(dir).use { s ->
            s.map { it.fileName.toString() }.filter { it !in known }.forEach {
                out +=
                    "$it: not listed in fonts.tsv"
            }
        }
        notices.forEach { n ->
            val text = Files.readString(dir.resolve(n))
            if (text.isBlank() || !text.contains(licenceText)) out += "$n: blank or without the licence text"
        }
        return out
    }

    @Test
    fun `INV-AC-24553 the committed resources are complete`() {
        assertEquals(emptyList<String>(), problems(resourceDir()))
    }

    @Test
    fun `INV-AC-24553 exactly two fonts are listed in the order Latin then Tamil`() {
        val rows = ExportFonts().records()
        assertEquals(listOf(latin, tamil), rows.map { it.file })
        assertEquals(listOf("Noto Sans", "Noto Sans Tamil"), rows.map { it.family })
    }

    @Test
    fun `INV-AC-24553 fonts tsv has the header and non-blank columns with OFL-1-1 and the digest of each file`() {
        val lines = Files.readAllLines(resourceDir().resolve("fonts.tsv")).filter { it.isNotBlank() }
        assertEquals(ExportFonts.HEADER, lines.first().split('\t'))
        assertEquals(3, lines.size)
        ExportFonts().records().forEach { r ->
            assertEquals("OFL-1.1", r.licence)
            assertTrue(listOf(r.family, r.version, r.upstreamUrl, r.copyright).none { it.isBlank() })
            assertEquals(
                sha(
                    ExportFonts::class.java.classLoader
                        .getResourceAsStream(r.file)!!
                        .use { it.readAllBytes() },
                ),
                r.sha256,
            )
        }
    }

    @Test
    fun `INV-AC-24553 the notice files hold the licence text as a substring`() {
        notices.forEach { n ->
            val text = Files.readString(resourceDir().resolve(n))
            assertFalse(text.isBlank())
            assertTrue(text.contains(licenceText), n)
        }
    }

    @Test
    fun `INV-AC-24553 no file other than the five is under fonts`() {
        val names = Files.list(resourceDir()).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(
            listOf(
                "NotoSans-OFL.txt",
                "NotoSans-Regular.ttf",
                "NotoSansTamil-OFL.txt",
                "NotoSansTamil-Regular.ttf",
                "fonts.tsv",
            ).sorted(),
            names,
        )
    }

    @Test
    fun `INV-AC-24553 a changed byte is found and names the file`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        flipByte(dir.resolve("NotoSansTamil-Regular.ttf"))
        assertTrue(problems(dir).any { it.startsWith(tamil) })
    }

    @Test
    fun `INV-AC-24553 a renamed font file is found and names the file`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        Files.move(dir.resolve("NotoSans-Regular.ttf"), dir.resolve("NotoSans-Regular2.ttf"))
        val found = problems(dir)
        assertTrue(found.any { it.startsWith(latin) }, found.toString())
        assertTrue(found.any { it.startsWith("NotoSans-Regular2.ttf") }, found.toString())
    }

    @Test
    fun `INV-AC-24553 a row with licence OFL-1-1-RFN is found`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        rewriteRecord(dir) { l -> l.map { it.replace("\tOFL-1.1\t", "\tOFL-1.1-RFN\t") } }
        assertTrue(problems(dir).any { it.startsWith("fonts/NotoSans-Regular.ttf") && it.contains("licence") })
    }

    @Test
    fun `INV-AC-24553 a row with an empty sha256 is found`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        rewriteRecord(dir) { l ->
            l.map { row ->
                row
                    .split('\t')
                    .mapIndexed { i, c ->
                        if (i == 4 &&
                            c.length == 64
                        ) {
                            ""
                        } else {
                            c
                        }
                    }.joinToString("\t")
            }
        }
        assertTrue(problems(dir).any { it.startsWith(latin) && it.contains("blank") })
    }

    @Test
    fun `INV-AC-24553 a third font file is found and named`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        Files.copy(dir.resolve("NotoSans-Regular.ttf"), dir.resolve("NotoSansSC-Regular.ttf"))
        assertEquals(listOf("NotoSansSC-Regular.ttf: not listed in fonts.tsv"), problems(dir))
    }

    @Test
    fun `INV-AC-24553 an empty notice file is found and a longer line holding the text passes`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        Files.writeString(dir.resolve("NotoSans-OFL.txt"), "")
        assertEquals(listOf("NotoSans-OFL.txt: blank or without the licence text"), problems(dir))
        Files.writeString(dir.resolve("NotoSans-OFL.txt"), "xx $licenceText - 26 February 2007 yy\n")
        assertEquals(emptyList<String>(), problems(dir))
    }

    @Test
    fun `INV-AC-24553 swapped rows are refused`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        rewriteRecord(dir) { l -> listOf(l[0], l[2], l[1]) }
        val e = assertThrows<IllegalStateException> { ExportFonts(dir).records() }
        assertTrue(e.message!!.contains("order"))
    }

    @Test
    fun `INV-AC-24553 a byte changed after one successful load makes the next load fail naming the file`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        val fonts = ExportFonts(dir)
        PDDocument().use { assertEquals(2, fonts.load(it).size) }
        flipByte(dir.resolve("NotoSans-Regular.ttf"))
        val e = assertThrows<IllegalStateException> { PDDocument().use { d -> fonts.load(d) } }
        assertTrue(e.message!!.contains(latin))
    }

    @Test
    fun `INV-AC-24553 load embeds each font as a subset`() {
        PDDocument().use { doc ->
            val loaded = ExportFonts().load(doc)
            assertEquals(2, loaded.size)
            assertTrue(loaded.all { it.isEmbedded })
        }
    }

    @Test
    fun `INV-AC-24553 fontBytes of each name equals the resource and the digest of its row`() {
        val fonts = ExportFonts()
        fonts.records().forEach { r ->
            val bytes = fonts.fontBytes(r.file)
            assertArrayEquals(
                ExportFonts::class.java.classLoader
                    .getResourceAsStream(r.file)!!
                    .use { it.readAllBytes() },
                bytes,
            )
            assertEquals(r.sha256, sha(bytes))
        }
    }

    @Test
    fun `INV-AC-24553 fontBytes after a changed byte throws naming the file`(
        @TempDir tmp: Path,
    ) {
        val dir = copy(tmp)
        val fonts = ExportFonts(dir)
        fonts.fontBytes(latin)
        flipByte(dir.resolve("NotoSans-Regular.ttf"))
        assertTrue(assertThrows<IllegalStateException> { fonts.fontBytes(latin) }.message!!.contains(latin))
    }

    @Test
    fun `INV-AC-24553 fontBytes of any other name throws IllegalArgumentException naming it`() {
        listOf(
            "fonts/NotoSans-Regular.TTF",
            "fonts/OFL.txt",
            "fonts/NotoSans-OFL.txt",
            "fonts/NotoSansSC-Regular.ttf",
            "",
            "fonts/../fonts/NotoSans-Regular.ttf",
        ).forEach { name ->
            val e = assertThrows<IllegalArgumentException> { ExportFonts().fontBytes(name) }
            assertTrue(e.message!!.contains(name))
        }
    }

    @Test
    fun `INV-AC-24553 writing into the returned array does not change the next result`() {
        val fonts = ExportFonts()
        val first = fonts.fontBytes(latin)
        val expected = first.copyOf()
        first.fill(0)
        val second = fonts.fontBytes(latin)
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(expected, second)
    }
}
