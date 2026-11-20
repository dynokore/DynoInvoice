package com.dynokore.gst.reconciliation.adapters.export

import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.harfbuzz.HarfBuzz
import java.util.concurrent.atomic.AtomicInteger

/** One shaped glyph: its glyph id in the font file, the index of the first UTF-16 unit of its cluster, its advance. */
internal data class ShapedGlyph(
    val glyphId: Int,
    val cluster: Int,
    val xAdvance: Int,
)

/** The glyphs of one run, in drawing order, and the units per em of the font that the advances are measured in. */
internal data class ShapedRun(
    val glyphs: List<ShapedGlyph>,
    val unitsPerEm: Int,
)

/**
 * Shapes one text run with HarfBuzz (the OpenType shaping engine; Unicode TR 17, the OpenType Tamil script
 * specification) and returns the glyph ids with their advances (INV-REQ-982). The font bytes are read only through
 * [ExportFonts.fontBytes], which verifies the digest at every call; this class reads no font resource itself. Every
 * native handle (blob, face, font, buffer) and the native memory of the font are released in a `finally`, and
 * nothing is cached between calls, so a result never outlives the call that made it.
 */
internal class TextShaper(
    private val fonts: ExportFonts,
) {
    /** Shapes [text] with the font file [fontFile] (a name [ExportFonts.fontBytes] accepts). */
    fun shape(
        text: String,
        fontFile: String,
    ): ShapedRun {
        val bytes = fonts.fontBytes(fontFile)
        Native(fontFile).use { native ->
            native.load(bytes)
            val buffer = native.buffer()
            HarfBuzz.hb_buffer_add_utf16(buffer, text, 0, text.length)
            HarfBuzz.hb_buffer_guess_segment_properties(buffer)
            HarfBuzz.hb_shape(native.font, buffer, null)
            val infos = HarfBuzz.hb_buffer_get_glyph_infos(buffer)
            val positions = HarfBuzz.hb_buffer_get_glyph_positions(buffer)
            val count = HarfBuzz.hb_buffer_get_length(buffer)
            val glyphs =
                (0 until count).map { i ->
                    ShapedGlyph(
                        infos?.get(i)?.codepoint() ?: 0,
                        infos?.get(i)?.cluster() ?: 0,
                        positions?.get(i)?.x_advance() ?: 0,
                    )
                }
            return ShapedRun(glyphs, HarfBuzz.hb_face_get_upem(native.face))
        }
    }

    /** The native handles of one call; [close] releases every one that was made, newest first. */
    private class Native(
        private val fontFile: String,
    ) : AutoCloseable {
        private var memory: java.nio.ByteBuffer? = null
        private var blob = 0L
        var face = 0L
            private set
        var font = 0L
            private set
        private var buffer = 0L

        fun load(bytes: ByteArray) {
            val block = MemoryUtil.memAlloc(bytes.size)
            memory = block
            handles.incrementAndGet()
            block.put(bytes).flip()
            blob = made(HarfBuzz.hb_blob_create(block, HarfBuzz.HB_MEMORY_MODE_READONLY, 0L, null), "blob")
            face = made(HarfBuzz.hb_face_create(blob, 0), "face")
            font = made(HarfBuzz.hb_font_create(face), "font")
        }

        fun buffer(): Long {
            buffer = made(HarfBuzz.hb_buffer_create(), "buffer")
            return buffer
        }

        private fun made(
            handle: Long,
            what: String,
        ): Long {
            check(handle != 0L) { "$fontFile: no $what" }
            handles.incrementAndGet()
            return handle
        }

        override fun close() {
            release(HarfBuzz::hb_buffer_destroy, buffer)
            release(HarfBuzz::hb_font_destroy, font)
            release(HarfBuzz::hb_face_destroy, face)
            release(HarfBuzz::hb_blob_destroy, blob)
            memory?.let {
                MemoryUtil.memFree(it)
                handles.decrementAndGet()
            }
        }

        private fun release(
            destroy: (Long) -> Unit,
            handle: Long,
        ) {
            if (handle == 0L) return
            destroy(handle)
            handles.decrementAndGet()
        }
    }

    companion object {
        private val handles = AtomicInteger()

        /** The number of native handles and native memory blocks held now; 0 when no call is running. */
        fun openHandles(): Int = handles.get()
    }
}
