package com.dynokore.gst.controlPlane.acceptance.gates

/** One gate finding; a gate fails when it returns any. */
data class Finding(
    val gate: String,
    val subject: String,
    val message: String,
) {
    override fun toString() = "[$gate] $subject: $message"
}

/** A TSV file: tab-separated, a header line, `#` comment lines and blank lines skipped. */
internal object Tsv {
    fun rows(text: String): Pair<List<String>, List<List<String>>>? {
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty()) return null
        return lines.first().split('\t').map { it.trim() } to lines.drop(1).map { line -> line.split('\t').map { it.trim() } }
    }
}
