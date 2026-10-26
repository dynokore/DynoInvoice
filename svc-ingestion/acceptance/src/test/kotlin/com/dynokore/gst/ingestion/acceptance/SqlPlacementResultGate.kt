package com.dynokore.gst.ingestion.acceptance

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `sqlPlacementGate` (INV-T-11026): run by the Gradle task of that name after `verifySqlPlacement` of each of the five
 * modules, over the result file each wrote on this build.
 */
class SqlPlacementResultGate {
    @Test
    fun `sqlPlacementGate`() {
        val root = File(System.getProperty("gst.root.dir"))
        val listed =
            System
                .getProperty("gst.projects")
                .orEmpty()
                .split(',')
                .filter { it.isNotEmpty() }
                .toSet()
        val reports =
            SqlPlacement.MODULES.associateWith { module ->
                File(root, "$module/build/gst/sql-placement/main.txt").takeIf { it.isFile }?.readText()
            }
        val outcomes =
            System
                .getProperty("gst.sqlplacement.outcomes")
                .orEmpty()
                .split(',')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        val findings = SqlPlacement.findings(listed, reports, outcomes)
        assertTrue(findings.isEmpty(), "sqlPlacementGate:\n" + findings.joinToString("\n"))
    }
}
