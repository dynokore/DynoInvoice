package com.dynokore.gst.consoleBff.acceptance

/**
 * The SQL placement gate (INV-T-14438; ruling INV-Q-7422), fails closed. `verifySqlPlacement` (platform-build,
 * INV-T-4135) writes `build/gst/sql-placement/main.txt` in each module, one line per finding and nothing for none.
 *
 * - the five modules are listed here by equality, never by a pattern: `console-bff-adapters-extra` is not one of them;
 * - the build lists fewer than the five modules: a finding naming the missing module;
 * - a module has no result file: a finding naming it;
 * - `verifySqlPlacement` of a module did not run on this build (disabled, skipped, no source): a finding naming it,
 *   because a file left by an earlier build says nothing about this one;
 * - a module has one finding or more: a finding naming it and the count. There is no exemption.
 */
object SqlPlacement {
    val MODULES =
        listOf(
            "console-bff-domain",
            "console-bff-application",
            "console-bff-adapters",
            "console-bff-bootstrap",
            "console-bff-contract",
        )

    /** The outcomes of the task that prove its result file belongs to this build. */
    val RAN = setOf("EXECUTED", "UP-TO-DATE", "FROM-CACHE")

    /**
     * [listed] is the set of project names of the build; [reports] the text of each module's file, null if absent;
     * [outcomes] the outcome of each module's `verifySqlPlacement` task on this build.
     */
    fun findings(
        listed: Set<String>,
        reports: Map<String, String?>,
        outcomes: Map<String, String>,
    ): List<String> =
        MODULES.flatMap { module ->
            val report = reports[module]
            when {
                module !in listed -> {
                    listOf("$module: the build does not list this module")
                }

                outcomes[module] !in RAN -> {
                    listOf("$module: verifySqlPlacement did not run (${outcomes[module]})")
                }

                report == null -> {
                    listOf("$module: build/gst/sql-placement/main.txt is missing")
                }

                else -> {
                    report.lines().count { it.isNotBlank() }.let { count ->
                        if (count ==
                            0
                        ) {
                            emptyList()
                        } else {
                            listOf("$module: $count finding(s) in build/gst/sql-placement/main.txt")
                        }
                    }
                }
            }
        }

    private val RAW_SQL = Regex("\\bDSL\\s*\\.\\s*sql\\s*\\(\\s*\"")

    /**
     * Source-text probe (ruling INV-Q-17576 A): a raw SQL string literal passed to `DSL.sql(...)` is a finding naming
     * the file and the line; there is no exemption. Comment lines are not code and give no finding. [text] is the
     * content of [file]; no jOOQ is needed on the classpath because the input is a string.
     */
    fun sourceFindings(
        file: String,
        text: String,
    ): List<String> =
        text.lines().mapIndexedNotNull { index, line ->
            val code = line.trimStart()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                null
            } else if (RAW_SQL.containsMatchIn(line)) {
                "$file:${index + 1}: raw SQL string passed to DSL.sql(...)"
            } else {
                null
            }
        }
}
