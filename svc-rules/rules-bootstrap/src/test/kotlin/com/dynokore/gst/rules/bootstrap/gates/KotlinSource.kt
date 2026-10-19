package com.dynokore.gst.rules.bootstrap.gates

/**
 * Kotlin source reduced to its code, for the repository gates (P140: a gate reads facts, and a comment or a string
 * is not a fact). Line comments and block comments, nested ones included, become blanks; string literals, raw strings
 * and character literals are kept as they are (emptied when `blankStrings`) and never searched for comment starts, so
 * `"http://x"` is not a comment.
 */
internal object KotlinSource {
    fun withoutComments(
        source: String,
        blankStrings: Boolean = false,
    ): String = Lexer(source, blankStrings).run()

    private class Lexer(
        private val source: String,
        private val blankStrings: Boolean,
    ) {
        private val out = StringBuilder(source.length)
        private var i = 0

        fun run(): String {
            while (i < source.length) step()
            return out.toString()
        }

        private fun step() {
            when {
                source.startsWith("//", i) -> skipLine()
                source.startsWith("/*", i) -> skipBlock()
                source.startsWith(RAW, i) -> rawString()
                source[i] == '"' -> string()
                source[i] == '\'' -> character()
                else -> out.append(source[i++])
            }
        }

        private fun skipLine() {
            while (i < source.length && source[i] != '\n') i++
        }

        private fun skipBlock() {
            var depth = 0
            do {
                val opens = source.startsWith("/*", i)
                val closes = source.startsWith("*/", i)
                if (opens) depth++
                if (closes) depth--
                i += if (opens || closes) 2 else 1
            } while (i < source.length && depth > 0)
            out.append(' ')
        }

        private fun rawString() {
            val end = source.indexOf(RAW, i + RAW.length).let { if (it < 0) source.length else it + RAW.length }
            if (blankStrings) out.append(RAW).append(RAW) else out.append(source, i, end)
            i = end
        }

        private fun string() {
            out.append(source[i++])
            while (i < source.length && source[i] != '"' && source[i] != '\n') {
                if (source[i] == '\\' && i + 1 < source.length) keep(source[i++])
                keep(source[i++])
            }
            if (i < source.length) out.append(source[i++])
        }

        private fun keep(c: Char) {
            if (!blankStrings) out.append(c)
        }

        private fun character() {
            val end = if (i + 1 < source.length && source[i + 1] == '\\') source.indexOf('\'', i + 3) else i + 2
            if (end in (i + 1)..<source.length && source[end] == '\'') {
                out.append(source, i, end + 1)
                i = end + 1
            } else {
                out.append(source[i++])
            }
        }
    }

    private const val RAW = "\"\"\""
}
