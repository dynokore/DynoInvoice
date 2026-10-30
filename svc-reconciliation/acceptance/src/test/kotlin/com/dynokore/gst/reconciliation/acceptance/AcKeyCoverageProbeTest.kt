package com.dynokore.gst.reconciliation.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table = "criterion\ttask\twave\nINV-AC-2189\tINV-T-5970\t4\nINV-AC-100\tINV-T-1\t5\n"
    private val names = listOf("INV-AC-2189 grants on work_queue()", "an unrelated test()")

    @Test
    fun `the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, listOf("an unrelated test()")).single() shouldContain "INV-AC-2189"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-21891 grants()"))
        findings.joinToString("\n") shouldContain "INV-AC-2189 (task INV-T-5970"
        findings.joinToString("\n") shouldContain "INV-AC-21891"
        AcKeyCoverage.findings(table, listOf("INV-AC-2189x grants()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-2189 grants()")).shouldNotBeEmpty()
    }

    @Test
    fun `a test name holding a key in no row fails`() {
        AcKeyCoverage.findings(table, names + "INV-AC-999 other()").joinToString() shouldContain "INV-AC-999"
    }

    @Test
    fun `an empty table, a header only table and a missing table fail`() {
        AcKeyCoverage.findings("", names).shouldNotBeEmpty()
        AcKeyCoverage.findings("criterion\ttask\twave\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(null, names).single() shouldContain "missing"
    }

    @Test
    fun `a malformed or duplicate row fails and a row of another wave needs no test`() {
        AcKeyCoverage.findings(table + "INV-AC-5\tTBD\t3\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-2189\tINV-T-2\t3\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-8\tINV-T-2\t3\n", names).shouldBeEmpty()
    }

    private fun withTask(cell: String) = "criterion\ttask\twave\nINV-AC-677\t$cell\t4\n"

    private val named = listOf("INV-AC-677 other()")

    @Test
    fun `a multi task cell in ascending order passes`() {
        AcKeyCoverage.findings(withTask("INV-T-7232,INV-T-7246"), named).shouldBeEmpty()
        AcKeyCoverage.findings(withTask("INV-T-8460,INV-T-11788"), named).shouldBeEmpty()
        AcKeyCoverage.findings(withTask("INV-T-7232,INV-T-7245,INV-T-7246"), named).shouldBeEmpty()
    }

    @Test
    fun `a malformed multi task cell fails`() {
        listOf(
            "INV-T-7232, INV-T-7246",
            "INV-T-7232,",
            ",INV-T-7232",
            "INV-T-7232,,INV-T-7246",
            "INV-T-7232,INV-T-7232",
            "INV-T-7246,INV-T-7232",
            "INV-T-11788,INV-T-8460",
            "INV-T-7232;INV-T-7246",
            "INV-T-7232|INV-T-7246",
            "INV-T-7232,INV-T-72x",
            "INV-T-7232,INV-T-7246x",
            "INV-T-7232,UNASSIGNED",
            "UNASSIGNED",
            "",
        ).forEach { cell ->
            AcKeyCoverage.findings(withTask(cell), named).joinToString() shouldContain "malformed row"
        }
    }

    @Test
    fun `two rows for one key still fail as a duplicate`() {
        val table = withTask("INV-T-7232") + "INV-AC-677\tINV-T-7246\t4\n"
        AcKeyCoverage.findings(table, named).joinToString() shouldContain "duplicate row"
    }

    @Test
    fun `an uncovered multi task row names the whole cell`() {
        val findings = AcKeyCoverage.findings(withTask("INV-T-7232,INV-T-7246"), listOf("an unrelated test()"))
        findings.single() shouldContain "INV-AC-677 (task INV-T-7232,INV-T-7246, wave 4)"
    }
}
