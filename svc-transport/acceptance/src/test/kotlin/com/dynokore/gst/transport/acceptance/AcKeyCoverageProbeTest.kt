package com.dynokore.gst.transport.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table = "criterion\ttask\twave\nINV-AC-1757\tINV-T-4981\t2\nINV-AC-442\tINV-T-4981\t3\n"
    private val names = listOf("INV-AC-1757 submission through the adapter()", "an unrelated test()")

    @Test
    fun `the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, listOf("an unrelated test()")).single() shouldContain "INV-AC-1757"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-17571 grants()"))
        findings.joinToString("\n") shouldContain "INV-AC-1757 (task INV-T-4981"
        findings.joinToString("\n") shouldContain "INV-AC-17571"
        AcKeyCoverage.findings(table, listOf("INV-AC-1757x grants()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-1757 grants()")).shouldNotBeEmpty()
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
        AcKeyCoverage.findings(table + "INV-AC-5\tTBD\t2\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-1757\tINV-T-2\t2\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
    }
}
