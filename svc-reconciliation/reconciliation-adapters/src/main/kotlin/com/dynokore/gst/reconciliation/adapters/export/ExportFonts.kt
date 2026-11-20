package com.dynokore.gst.reconciliation.adapters.export

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** One row of `fonts/fonts.tsv`. */
internal data class FontRecord(
    val file: String,
    val family: String,
    val version: String,
    val upstreamUrl: String,
    val sha256: String,
    val licence: String,
    val copyright: String,
)

/**
 * The bundled Noto fonts of the PDF writer (INV-REQ-982) and the single owner of their bytes. The record
 * `fonts/fonts.tsv` binds every font file to a SHA-256 digest and the licence OFL-1.1; the record and every digest are
 * verified at each call, and nothing (digest, bytes, font) is remembered between calls (OWASP SCVS; fail closed with
 * an [IllegalStateException] that names the file). [directory] is the `fonts` directory to read instead of the
 * classpath resources under `fonts`; it exists so a test can alter copies of the files.
 */
internal class ExportFonts(
    private val directory: Path? = null,
) {
    /** The two fonts, Latin then Tamil; each is verified, then embedded as a subset (ISO 32000-1 9.6.4). */
    fun load(document: PDDocument): List<PDType0Font> =
        records().map { record ->
            ByteArrayInputStream(verified(record)).use { PDType0Font.load(document, it, true) }
        }

    /** A fresh copy of the verified bytes of one of the two font resources; any other name is refused. */
    fun fontBytes(file: String): ByteArray {
        require(file in FILES) { "not a bundled font resource: $file" }
        return verified(records().first { it.file == file })
    }

    /** The parsed and checked record, re-read at every call. */
    fun records(): List<FontRecord> {
        val lines = String(read(RECORD), Charsets.UTF_8).lines().filter { it.isNotBlank() }
        check(lines.firstOrNull()?.split('\t') == HEADER) { "$RECORD: header is not ${HEADER.joinToString("\t")}" }
        val records =
            lines.drop(1).map { line ->
                val c = line.split('\t')
                check(c.size == HEADER.size) { "$RECORD: a row has ${c.size} columns: ${c.firstOrNull()}" }
                FontRecord(c[0], c[1], c[2], c[3], c[4], c[5], c[6])
            }
        check(
            records.map { it.file } == FILES,
        ) { "$RECORD: the rows are not exactly ${FILES.joinToString(", ")} in order" }
        records.forEach { r ->
            check(listOf(r.family, r.version, r.upstreamUrl, r.sha256, r.copyright).none { it.isBlank() }) {
                "${r.file}: a column of its row is blank"
            }
            check(r.licence == LICENCE) { "${r.file}: licence is not $LICENCE" }
        }
        return records
    }

    private fun verified(record: FontRecord): ByteArray {
        val bytes = read(record.file)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(digest == record.sha256) { "${record.file}: SHA-256 differs from its row in $RECORD" }
        return bytes
    }

    private fun read(name: String): ByteArray =
        try {
            if (directory != null) {
                Files.readAllBytes(directory.resolve(name.removePrefix("fonts/")))
            } else {
                checkNotNull(ExportFonts::class.java.classLoader.getResourceAsStream(name)) { "$name: missing" }
                    .use { it.readAllBytes() }
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("$name: cannot be read", e)
        }

    companion object {
        const val RECORD = "fonts/fonts.tsv"
        const val LICENCE = "OFL-1.1"
        val FILES = listOf("fonts/NotoSans-Regular.ttf", "fonts/NotoSansTamil-Regular.ttf")
        val HEADER = listOf("file", "family", "version", "upstream_url", "sha256", "licence", "copyright")
    }
}
