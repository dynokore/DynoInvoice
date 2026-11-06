package com.dynokore.gst.consoleBff.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table =
        "criterion\ttask\twave\nINV-AC-14437\tINV-T-14438\t2\nINV-AC-100\tINV-T-1\t5\nINV-AC-200\tINV-T-2\t4\n"
    private val names = listOf("INV-AC-14437 and INV-AC-200 are covered()", "an unrelated test()")

    @Test
    fun `INV-AC-14437 the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-14437 deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, listOf("INV-AC-200 covered()")).single() shouldContain "INV-AC-14437"
    }

    @Test
    fun `INV-AC-14437 renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-144370 grants()"))
        findings.joinToString("\n") shouldContain "INV-AC-14437 (task INV-T-14438"
        findings.joinToString("\n") shouldContain "INV-AC-144370"
        AcKeyCoverage.findings(table, listOf("INV-AC-14437x grants()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-14437 grants()")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-14437 a wave 4 row with no test fails and a wave 5 row needs none`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-14437 only()"))
        findings.single() shouldContain "INV-AC-200 (task INV-T-2, wave 4)"
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-14437 a test name holding a key in no row fails`() {
        AcKeyCoverage.findings(table, names + "INV-AC-999 other()").joinToString() shouldContain "INV-AC-999"
    }

    @Test
    fun `INV-AC-14437 an empty table, a header only table and a missing table fail`() {
        AcKeyCoverage.findings("", names).shouldNotBeEmpty()
        AcKeyCoverage.findings("criterion\ttask\twave\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(null, names).single() shouldContain "missing"
    }

    @Test
    fun `INV-AC-14437 a malformed or duplicate row fails and a row of another wave needs no test`() {
        AcKeyCoverage.findings(table + "INV-AC-5\tTBD\t2\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-14437\tINV-T-2\t2\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
    }
}
