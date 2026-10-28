package com.dynokore.gst.canonical.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table = "criterion\ttask\twave\nINV-AC-2177\tINV-T-5544\t3\nINV-AC-100\tINV-T-1\t5\n"
    private val names = listOf("INV-AC-2177 records the event()", "an unrelated test()")

    @Test
    fun `the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, listOf("an unrelated test()")).single() shouldContain "INV-AC-2177"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-21771 grants()"))
        findings.joinToString("\n") shouldContain "INV-AC-2177 (task INV-T-5544"
        findings.joinToString("\n") shouldContain "INV-AC-21771"
        AcKeyCoverage.findings(table, listOf("INV-AC-2177x grants()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-2177 grants()")).shouldNotBeEmpty()
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
        AcKeyCoverage.findings(table + "INV-AC-2177\tINV-T-2\t3\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
    }

    private fun row(
        key: String,
        wave: Int,
    ) = "criterion\ttask\twave\n$key\tINV-T-9\t$wave\n"

    private val unrelated = listOf("an unrelated test()")

    @Test
    fun `INV-AC-12289 a row of wave 2 without a test gives one finding naming the key, the task and the wave`() {
        val finding = AcKeyCoverage.findings(table + "INV-AC-2178\tINV-T-8271\t2\n", names).single()
        finding shouldContain "INV-AC-2178 (task INV-T-8271, wave 2)"
    }

    @Test
    fun `INV-AC-12289 a row of wave 2 with an executed test of the key gives no finding`() {
        val withRow = table + "INV-AC-2178\tINV-T-8271\t2\n"
        AcKeyCoverage.findings(withRow, names + "INV-AC-2178 holds()").shouldBeEmpty()
    }

    @Test
    fun `INV-AC-12289 a row of wave 3 without a test still gives its finding`() {
        AcKeyCoverage.findings(row("INV-AC-2178", 3), unrelated).joinToString() shouldContain
            "INV-AC-2178 (task INV-T-9, wave 3)"
    }

    @Test
    fun `INV-AC-12289 a row of wave 1 and a row of wave 0 without a test each give a finding`() {
        AcKeyCoverage.findings(row("INV-AC-2178", 1), unrelated).single() shouldContain
            "INV-AC-2178 (task INV-T-9, wave 1)"
        AcKeyCoverage.findings(row("INV-AC-2178", 0), unrelated).single() shouldContain
            "INV-AC-2178 (task INV-T-9, wave 0)"
    }

    @Test
    fun `INV-AC-12289 a row of wave 4 without a test gives no finding`() {
        AcKeyCoverage.findings(row("INV-AC-2178", 4), unrelated).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-12289 a wave 2 row with a longer look alike test name gives the row and the look alike finding`() {
        val findings = AcKeyCoverage.findings(row("INV-AC-8260", 2), listOf("INV-AC-82600 grants()"))
        findings.size shouldBe 2
        findings.joinToString("\n") shouldContain "INV-AC-8260 (task INV-T-9, wave 2)"
        findings.joinToString("\n") shouldContain "test 'INV-AC-82600 grants()' holds INV-AC-82600, which is in no row"
    }
}
