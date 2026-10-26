package com.dynokore.gst.ingestion.acceptance

/**
 * The SQL placement gate (INV-T-11026; ruling INV-Q-7422), fails closed. `verifySqlPlacement` (platform-build,
 * INV-T-4135) writes `build/gst/sql-placement/main.txt` in each module, one line per finding and nothing for none.
 *
 * - the five modules are listed here by equality, never by a pattern: `ingestion-adapters-extra` is not one of them;
 * - the build lists fewer than the five modules: a finding naming the missing module;
 * - a module has no result file: a finding naming it;
 * - `verifySqlPlacement` of a module did not run on this build (disabled, skipped, no source): a finding naming it,
 *   because a file left by an earlier build says nothing about this one;
 * - a module has one finding or more: a finding naming it and the count. There is no exemption.
 */
object SqlPlacement {
    val MODULES =
        listOf(
            "ingestion-domain",
            "ingestion-application",
            "ingestion-adapters",
            "ingestion-bootstrap",
            "ingestion-contract",
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
}
