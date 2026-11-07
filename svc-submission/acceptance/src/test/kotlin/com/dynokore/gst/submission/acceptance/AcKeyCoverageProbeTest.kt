package com.dynokore.gst.submission.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table = "criterion\ttask\twave\nINV-AC-2180\tINV-T-5964\t3\nINV-AC-100\tINV-T-1\t5\n"
    private val names = listOf("INV-AC-2180 grants on work_queue()", "an unrelated test()")

    @Test
    fun `the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, listOf("an unrelated test()")).single() shouldContain "INV-AC-2180"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-21801 grants()"))
        findings.joinToString("\n") shouldContain "INV-AC-2180 (task INV-T-5964"
        findings.joinToString("\n") shouldContain "INV-AC-21801"
        AcKeyCoverage.findings(table, listOf("INV-AC-2180x grants()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-2180 grants()")).shouldNotBeEmpty()
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
        AcKeyCoverage.findings(table + "INV-AC-2180\tINV-T-2\t3\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
    }

    private val waveTable =
        "criterion\ttask\twave\nINV-AC-2\tINV-T-2\t2\nINV-AC-3\tINV-T-3\t3\nINV-AC-4\tINV-T-4\t4\n"

    @Test
    fun `a wave 2 row without a test is a finding`() {
        val f = AcKeyCoverage.findings(waveTable, listOf("INV-AC-3 a()"))
        f.single() shouldBe "INV-AC-2 (task INV-T-2, wave 2): no executed test name holds the key as a whole token"
    }

    @Test
    fun `a wave 3 row without a test is a finding`() {
        val f = AcKeyCoverage.findings(waveTable, listOf("INV-AC-2 a()"))
        f.single() shouldBe "INV-AC-3 (task INV-T-3, wave 3): no executed test name holds the key as a whole token"
    }

    @Test
    fun `a wave 4 row without a test is no finding`() {
        AcKeyCoverage.findings(waveTable, listOf("INV-AC-2 a()", "INV-AC-3 b()")).shouldBeEmpty()
    }

    @Test
    fun `a wave 2 row whose test holds the look alike of a wave 2 key gives both findings`() {
        val t = "criterion\ttask\twave\nINV-AC-9344\tINV-T-9347\t2\n"
        val f = AcKeyCoverage.findings(t, listOf("INV-AC-93440 x()"))
        f.size shouldBe 2
        f[0] shouldBe "INV-AC-9344 (task INV-T-9347, wave 2): no executed test name holds the key as a whole token"
        f[1] shouldContain "INV-AC-93440"
    }

    @Test
    fun `the real table without the test of the wave 2 row of task 9347 is a finding`() {
        val table = File(System.getProperty("gst.root.dir"), "ac-keys.tsv").readText()
        val keys = AcKeyCoverage.parse(table).first.map { it.criterion }
        AcKeyCoverage.findings(table, keys.map { "$it t()" }).shouldBeEmpty()
        val f = AcKeyCoverage.findings(table, keys.filter { it != "INV-AC-9344" }.map { "$it t()" })
        f.single() shouldBe
            "INV-AC-9344 (task INV-T-9347, wave 2): no executed test name holds the key as a whole token"
    }
}
