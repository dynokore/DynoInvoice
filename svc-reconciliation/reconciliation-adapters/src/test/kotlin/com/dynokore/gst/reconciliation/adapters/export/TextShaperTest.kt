package com.dynokore.gst.reconciliation.adapters.export

import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * INV-AC-24750: the glyph ids that [TextShaper] returns equal, element by element, what `hb-shape` prints for the same
 * font file and string. The constants below were recorded with `hb-shape (HarfBuzz) 6.0.0`, command
 * `hb-shape --shapers=ot --no-glyph-names --no-clusters fonts/NotoSansTamil-Regular.ttf "<text>"`
 * (output `[gid+advance|...]`).
 */
class TextShaperTest {
    private val fonts = ExportFonts()
    private val shaper = TextShaper(fonts)
    private val tamil = "fonts/NotoSansTamil-Regular.ttf"
    private val latin = "fonts/NotoSans-Regular.ttf"
    private val ka = "க"

    private class Fixture(
        val name: String,
        val text: String,
        val glyphIds: List<Int>,
        val advances: List<Int>,
        val preBaseOrTwoPart: Boolean,
    )

    // hb-shape 6.0.0 output, Noto Sans Tamil 2.004
    private val fixtures =
        listOf(
            Fixture("U+0BC6", "கெ", listOf(46, 18), listOf(901, 825), true),
            Fixture("U+0BC7", "கே", listOf(47, 18), listOf(720, 825), true),
            Fixture("U+0BC8", "கை", listOf(48, 18), listOf(1160, 825), true),
            Fixture("U+0BCA", "கொ", listOf(46, 18, 41), listOf(901, 825, 640), true),
            Fixture("U+0BCB", "கோ", listOf(47, 18, 41), listOf(720, 825, 640), true),
            Fixture("U+0BCC", "கௌ", listOf(46, 18, 54), listOf(901, 825, 1070), true),
            Fixture("U+0BCD conjunct", "க்ஷ", listOf(76), listOf(1851), false),
        )

    /** The glyph ids of the Unicode cmap of the font, one per code point: the unshaped sequence. */
    private fun unshaped(
        file: String,
        text: String,
    ): List<Int> {
        val ttf = TTFParser().parse(RandomAccessReadBuffer(fonts.fontBytes(file)))
        ttf.use { f ->
            val cmap = f.unicodeCmapLookup
            return text.codePoints().toArray().map { cmap.getGlyphId(it) }
        }
    }

    @Test
    fun `INV-AC-24750 the shaped glyph ids of each Tamil fixture equal the hb-shape sequence`() {
        fixtures.forEach {
            val run = shaper.shape(it.text, tamil)
            assertEquals(it.glyphIds, run.glyphs.map { g -> g.glyphId }, it.name)
        }
    }

    @Test
    fun `INV-AC-24750 the shaped advances of each Tamil fixture equal the hb-shape advances`() {
        fixtures.forEach {
            val run = shaper.shape(it.text, tamil)
            assertEquals(it.advances, run.glyphs.map { g -> g.xAdvance }, it.name)
            assertEquals(1000, run.unitsPerEm)
        }
    }

    @Test
    fun `INV-AC-24750 the shaped sequence differs from the unshaped cmap one for the pre-base and two-part cases`() {
        fixtures.filter { it.preBaseOrTwoPart }.forEach {
            val run = shaper.shape(it.text, tamil).glyphs.map { g -> g.glyphId }
            assertNotEquals(unshaped(tamil, it.text), run, it.name)
        }
        // the unshaped sequence of U+0BC6 is the consonant, then the vowel sign: the sign is not drawn first
        assertEquals(18, unshaped(tamil, fixtures[0].text).first())
        assertEquals(
            46,
            shaper
                .shape(fixtures[0].text, tamil)
                .glyphs
                .first()
                .glyphId,
        )
    }

    @Test
    fun `INV-AC-24750 in a two-part vowel the pre-base part is drawn first and the consonant second`() {
        val shaped = shaper.shape(fixtures[3].text, tamil).glyphs.map { it.glyphId }
        assertEquals(listOf(46, 18, 41), shaped)
        assertNotEquals(listOf(18, 46, 41), shaped)
    }

    @Test
    fun `INV-AC-24750 a text without Tamil shaping rules is not reordered and a missing glyph is glyph 0`() {
        assertEquals(unshaped(latin, "Ab1"), shaper.shape("Ab1", latin).glyphs.map { it.glyphId })
        assertEquals(listOf(0), shaper.shape(ka, latin).glyphs.map { it.glyphId })
    }

    @Test
    fun `INV-AC-24750 a result is never reused for a different font file`() {
        val first = shaper.shape(ka, tamil).glyphs.map { it.glyphId }
        val other = shaper.shape(ka, latin).glyphs.map { it.glyphId }
        val again = shaper.shape(ka, tamil).glyphs.map { it.glyphId }
        assertEquals(listOf(18), first)
        assertEquals(listOf(0), other)
        assertEquals(first, again)
    }

    @Test
    fun `INV-AC-24750 no native handle stays open after a shape call or a refused font name`() {
        assertEquals(0, TextShaper.openHandles())
        repeat(20) { fixtures.forEach { f -> shaper.shape(f.text, tamil) } }
        assertEquals(0, TextShaper.openHandles())
        runCatching { shaper.shape(ka, "fonts/NotoSansTamil-Regular.ttf.bak") }
        assertEquals(0, TextShaper.openHandles())
    }

    @Test
    fun `INV-AC-24750 the font bytes are read only through ExportFonts so an altered font is refused`() {
        val tmp =
            java.nio.file.Path
                .of(System.getProperty("java.io.tmpdir"))
        val dir =
            java.nio.file.Files
                .createTempDirectory(tmp, "shaper")
        try {
            ExportFonts::class.java.classLoader.getResourceAsStream("fonts/fonts.tsv")!!.use {
                java.nio.file.Files
                    .write(dir.resolve("fonts.tsv"), it.readAllBytes())
            }
            ExportFonts.FILES.forEach { f ->
                val bytes = fonts.fontBytes(f)
                if (f == tamil) bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
                java.nio.file.Files
                    .write(dir.resolve(f.removePrefix("fonts/")), bytes)
            }
            val refused = runCatching { TextShaper(ExportFonts(dir)).shape(ka, tamil) }.exceptionOrNull()
            assertEquals(IllegalStateException::class, refused?.let { it::class })
            assertEquals(0, TextShaper.openHandles())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
