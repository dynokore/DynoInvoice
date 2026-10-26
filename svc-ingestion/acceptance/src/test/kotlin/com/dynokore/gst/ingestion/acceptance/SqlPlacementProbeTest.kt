package com.dynokore.gst.ingestion.acceptance

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
    fun `five clean modules pass`() {
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean).shouldBeEmpty()
    }

    @Test
    fun `one finding in a module fails naming the module`() {
        val reports = clean + ("ingestion-adapters" to "com.X.run: raw SQL\n")
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), reports).single() shouldContain
            "ingestion-adapters: 1 finding"
        val other = clean + ("ingestion-bootstrap" to "a\nb\n")
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), other).single() shouldContain
            "ingestion-bootstrap: 2 finding"
    }

    @Test
    fun `a task that did not run on this build fails naming the module`() {
        for (outcome in listOf("SKIPPED", "NO-SOURCE", "FAILED")) {
            val outcomes = ran + ("ingestion-contract" to outcome)
            SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean, outcomes).single() shouldContain
                "ingestion-contract: verifySqlPlacement did not run ($outcome)"
        }
        SqlPlacement
            .run(
                SqlPlacement.MODULES.toSet(),
                clean,
                ran + ("ingestion-contract" to "UP-TO-DATE"),
            ).shouldBeEmpty()
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean, ran - "ingestion-contract").single() shouldContain
            "(null)"
    }

    @Test
    fun `a module without a result file fails naming the module`() {
        val reports = clean + ("ingestion-contract" to null)
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), reports).single() shouldContain
            "ingestion-contract: build/gst"
        SqlPlacement.run(SqlPlacement.MODULES.toSet(), clean - "ingestion-domain").single() shouldContain
            "ingestion-domain"
    }

    @Test
    fun `a build that lists fewer than five modules fails`() {
        val listed = SqlPlacement.MODULES.toSet() - "ingestion-application"
        SqlPlacement.run(listed, clean).single() shouldContain "ingestion-application: the build does not list"
        SqlPlacement.run(emptySet(), clean) shouldHaveSize 5
    }

    @Test
    fun `a module named like one of the five is not counted as one`() {
        val listed = SqlPlacement.MODULES.toSet() - "ingestion-adapters" + "ingestion-adapters-extra"
        val reports = clean + ("ingestion-adapters-extra" to "")
        SqlPlacement.run(listed, reports).single() shouldContain "ingestion-adapters: the build does not list"
        // a clean look-alike does not stand in for a missing result file of the real module either
        SqlPlacement
            .run(
                SqlPlacement.MODULES.toSet() + "ingestion-adapters-extra",
                clean - "ingestion-adapters" + ("ingestion-adapters-extra" to ""),
            ).single() shouldContain "ingestion-adapters: build/gst"
    }
}
