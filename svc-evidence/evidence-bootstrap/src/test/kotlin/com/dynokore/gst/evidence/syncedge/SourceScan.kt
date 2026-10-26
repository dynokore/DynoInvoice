package com.dynokore.gst.evidence.syncedge

import java.nio.file.Files
import java.nio.file.Path

/** The source scan of INV-AC-8455: no main source set holds a caller name or passes a literal to a caller check. */
object SourceScan {
    /** A string literal as the argument of a caller check: `ServiceCaller...("x")`, `callerCheck("x")`. */
    private val CALLER_CHECK = Regex("""(ServiceCaller|callerCheck|requireCaller)\b[^;\n]*\(\s*"[^"]*"""")

    /** The findings in the `src/main` of every module under [root]. */
    fun findings(
        root: Path,
        names: Set<String>,
    ): List<String> =
        files(root).flatMap { file ->
            val code = withoutComments(Files.readString(file))
            val check =
                if (CALLER_CHECK.containsMatchIn(
                        code,
                    )
                ) {
                    listOf("$file: a literal passed to a caller check")
                } else {
                    emptyList()
                }
            check + names.filter { "\"$it\"" in code }.map { "$file: the literal \"$it\"" }
        }

    private fun files(root: Path): List<Path> =
        Files
            .list(root)
            .use { modules -> modules.toList() }
            .map { it.resolve("src/main") }
            .filter(Files::isDirectory)
            .flatMap { main -> Files.walk(main).use { all -> all.toList() } }
            .filter { it.toString().endsWith(".kt") }

    /** Comments blanked (nested block comments included); string literals stay, a name in one is what is looked for. */
    fun withoutComments(source: String): String {
        val out = StringBuilder()
        var index = 0
        var depth = 0
        var inString = false
        while (index < source.length) {
            val two = source.substring(index, minOf(index + 2, source.length))
            val char = source[index]
            when {
                depth > 0 -> {
                    depth += blockDelta(two)
                    index += if (two == "/*" || two == "*/") 2 else 1
                }

                inString -> {
                    out.append(char)
                    if (char == '\\' &&
                        index + 1 < source.length
                    ) {
                        out.append(source[++index])
                    } else {
                        inString = char != '"'
                    }
                    index++
                }

                two == "/*" -> {
                    depth++
                    index += 2
                }

                two == "//" -> {
                    index = lineEnd(source, index)
                }

                else -> {
                    inString = char == '"'
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun blockDelta(two: String): Int =
        when (two) {
            "/*" -> 1
            "*/" -> -1
            else -> 0
        }

    private fun lineEnd(
        source: String,
        from: Int,
    ): Int = source.indexOf('\n', from).let { if (it < 0) source.length else it }
}
