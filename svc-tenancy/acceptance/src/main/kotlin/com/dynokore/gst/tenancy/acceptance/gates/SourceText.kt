package com.dynokore.gst.tenancy.acceptance.gates

/** Kotlin source text without comments, so a gate never reads a name that only a comment or a string holds. */
internal object SourceText {
    private const val TRIPLE = "\"\"\""
    private val STRING = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")

    /** Removes line comments and nested block comments; string literals are kept as they are. */
    fun stripComments(source: String): String = Scanner(source).run()

    /** Comments and string literals removed, for rules that look for code only. */
    fun stripCommentsAndStrings(source: String): String = stripComments(source).replace(STRING, "\"\"")

    private class Scanner(
        private val source: String,
    ) {
        private val out = StringBuilder()
        private var i = 0

        fun run(): String {
            while (i < source.length) step()
            return out.toString()
        }

        private fun step() {
            when {
                source.startsWith("//", i) -> skipLine()
                source.startsWith("/*", i) -> skipBlock()
                source.startsWith(TRIPLE, i) -> copyRaw()
                source[i] == '"' -> copyString()
                else -> out.append(source[i++])
            }
        }

        private fun skipLine() {
            while (i < source.length && source[i] != '\n') i++
        }

        private fun skipBlock() {
            var depth = 0
            do {
                when {
                    source.startsWith("/*", i) -> depth++.also { i += 2 }
                    source.startsWith("*/", i) -> depth--.also { i += 2 }
                    else -> i++
                }
            } while (depth > 0 && i < source.length)
        }

        private fun copyRaw() {
            val end =
                source.indexOf(TRIPLE, i + TRIPLE.length).let {
                    if (it <
                        0
                    ) {
                        source.length
                    } else {
                        it + TRIPLE.length
                    }
                }
            out.append(source, i, end)
            i = end
        }

        private fun copyString() {
            out.append(source[i++])
            while (i < source.length && source[i] != '"') {
                if (source[i] == '\\' && i + 1 < source.length) out.append(source[i++])
                out.append(source[i++])
            }
            if (i < source.length) out.append(source[i++])
        }
    }
}
