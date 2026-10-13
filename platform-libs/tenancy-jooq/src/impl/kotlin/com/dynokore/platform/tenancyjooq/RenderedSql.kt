package com.dynokore.platform.tenancyjooq

/**
 * The lexical check of a rendered statement (INV-T-4133; review of !61), the last line behind [StatementGuard]'s
 * refusal of plain SQL: outside string literals and quoted names, a statement that tenancy-jooq lets through never
 * holds a comment (a double dash, or a slash and star in either order), a statement separator (`;`), dollar
 * quoting (`$`) or a backslash, and its literals, quoted names and parentheses are balanced. Any of them means SQL
 * text reached the statement.
 */
internal object RenderedSql {
    fun require(sql: String) {
        val problem = problem(sql) ?: return
        throw TenantPredicateViolation("the rendered statement is refused: it holds $problem")
    }

    fun problem(sql: String): String? {
        var depth = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> i = endOfLiteral(sql, i, backslashEscapes = isEscapeString(sql, i)) ?: return UNTERMINATED

                c == '"' -> i = endOfLiteral(sql, i, backslashEscapes = false) ?: return UNTERMINATED

                else -> {
                    tokenProblem(sql, i)?.let { return it }
                    depth += if (c == '(') {
                        1
                    } else if (c == ')') {
                        -1
                    } else {
                        0
                    }
                    if (depth < 0) return UNBALANCED
                    i++
                }
            }
        }
        return if (depth == 0) null else UNBALANCED
    }

    private fun tokenProblem(
        sql: String,
        i: Int,
    ): String? = when {
        sql.startsWith("--", i) -> "a line comment (--)"
        sql.startsWith("/*", i) || sql.startsWith("*/", i) -> "a block comment (/* */)"
        sql[i] == ';' -> "a statement separator (;)"
        sql[i] == '$' -> "a dollar sign outside a literal (dollar quoting)"
        sql[i] == '\\' -> "a backslash outside a literal"
        else -> null
    }

    /** `E'...'` (PostgreSQL's escape string) takes backslash escapes; `'...'` and `"..."` only a doubled quote. */
    private fun isEscapeString(
        sql: String,
        quote: Int,
    ): Boolean = quote > 0 &&
        (sql[quote - 1] == 'E' || sql[quote - 1] == 'e') &&
        (quote < 2 || !(sql[quote - 2].isLetterOrDigit() || sql[quote - 2] == '_' || sql[quote - 2] == '"'))

    /** The index after the literal or quoted name opening at [start], or null when it never closes. */
    private fun endOfLiteral(
        sql: String,
        start: Int,
        backslashEscapes: Boolean,
    ): Int? {
        val quote = sql[start]
        var i = start + 1
        while (i < sql.length) {
            when {
                backslashEscapes && sql[i] == '\\' -> i += 2
                sql[i] == quote && sql.getOrNull(i + 1) == quote -> i += 2
                sql[i] == quote -> return i + 1
                else -> i++
            }
        }
        return null
    }

    private const val UNTERMINATED = "an unterminated string literal or quoted name"
    private const val UNBALANCED = "unbalanced parentheses"
}
