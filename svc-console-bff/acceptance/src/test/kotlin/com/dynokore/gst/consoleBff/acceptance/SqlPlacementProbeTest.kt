package com.dynokore.gst.consoleBff.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the SQL placement gate: each names the module it must refuse. */
class SqlPlacementProbeTest {
    private val clean = SqlPlacement.MODULES.associateWith { "" as String? }
    private val ran = SqlPlacement.MODULES.associateWith { "EXECUTED" }

    private fun SqlPlacement.run(
        listed: Set<String>,
        reports: Map<String, String?>,
        outcomes: Map<String, String> = ran,
    ) = findings(listed, reports, outcomes)

    @Test
    fun `INV-AC-14437 five clean modules pass`() {
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-14437 one finding in a module fails naming the module`() {
        val reports = clean + ("console-bff-adapters" to "com.X.run: raw SQL\n")
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), reports).single() shouldContain
            "console-bff-adapters: 1 finding"
        val other = clean + ("console-bff-bootstrap" to "a\nb\n")
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), other).single() shouldContain
            "console-bff-bootstrap: 2 finding"
    }

    @Test
    fun `INV-AC-14437 a task that did not run on this build fails naming the module`() {
        for (outcome in listOf("SKIPPED", "NO-SOURCE", "FAILED")) {
            val outcomes = ran + ("console-bff-contract" to outcome)
            SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean, outcomes).single() shouldContain
                "console-bff-contract: verifySqlPlacement did not run ($outcome)"
        }
        SqlPlacement
            .run(
                SqlPlacement.MODULES.toSet(),
                clean,
                ran + ("console-bff-contract" to "UP-TO-DATE"),
            ).shouldBeEmpty()
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean, ran - "console-bff-contract").single() shouldContain
            "(null)"
    }

    @Test
    fun `INV-AC-14437 a module without a result file fails naming the module`() {
        val reports = clean + ("console-bff-contract" to null)
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), reports).single() shouldContain
            "console-bff-contract: build/gst"
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean - "console-bff-domain").single() shouldContain
            "console-bff-domain"
    }

    @Test
    fun `INV-AC-14437 a build that lists fewer than five modules fails`() {
        val listed = SqlPlacement.MODULES.toSet() - "console-bff-application"
        SqlPlacement.run(listed, clean).single() shouldContain "console-bff-application: the build does not list"
        SqlPlacement.run(emptySet(), clean) shouldHaveSize 5
    }

    @Test
    fun `INV-AC-14437 a module named like one of the five is not counted as one`() {
        val listed = SqlPlacement.MODULES.toSet() - "console-bff-adapters" + "console-bff-adapters-extra"
        val reports = clean + ("console-bff-adapters-extra" to "")
        SqlPlacement.run(listed, reports).single() shouldContain "console-bff-adapters: the build does not list"
        // a clean look-alike does not stand in for a missing result file of the real module either
        SqlPlacement
            .run(
                SqlPlacement.MODULES.toSet() + "console-bff-adapters-extra",
                clean - "console-bff-adapters" + ("console-bff-adapters-extra" to ""),
            ).single() shouldContain "console-bff-adapters: build/gst"
    }
}
