package com.dynokore.gst.aggregation.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table =
        "criterion\ttask\twave\nINV-AC-447\tINV-T-8451\t2\nINV-AC-1572\tINV-T-8451\t2\n" +
            "INV-AC-1436\tINV-T-11349\t2\nINV-AC-453\tINV-T-11286\t2\nINV-AC-2779\tINV-T-11286\t2\n" +
            "INV-AC-100\tINV-T-1\t4\n"
    private val names =
        listOf(
            "INV-AC-447 rejects()",
            "INV-AC-1572 blocks()",
            "INV-AC-1436 numbers()",
            "INV-AC-453 replays()",
            "INV-AC-2779 keeps()",
            "an unrelated test()",
        )

    @Test
    fun `the table and a matching test pass`() {
        AcKeyCoverage.findings(table, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, names.filter { !it.startsWith("INV-AC-447") }).single() shouldContain
            "INV-AC-447"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, listOf("INV-AC-4470 rejects()", "INV-AC-1572 blocks()"))
        findings.joinToString("\n") shouldContain "INV-AC-447 (task INV-T-8451"
        findings.joinToString("\n") shouldContain "INV-AC-4470"
        AcKeyCoverage.findings(table, listOf("INV-AC-447x rejects()", "INV-AC-1572 blocks()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, listOf("xINV-AC-447 rejects()", "INV-AC-1572 blocks()")).shouldNotBeEmpty()
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
        AcKeyCoverage.findings(table + "INV-AC-447\tINV-T-2\t4\n", names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", names).shouldNotBeEmpty()
    }

    @Test
    fun `a row of another slice carrying this task key fails naming the row`() {
        AcKeyCoverage.findings(table + "INV-AC-457\tINV-T-8451\t2\n", names + "INV-AC-457 x()").single() shouldContain
            "INV-AC-457 (task INV-T-8451): not among the ac_refs"
    }

    @Test
    fun `a look alike row carrying this task key fails naming the row`() {
        AcKeyCoverage.findings(table + "INV-AC-4470\tINV-T-8451\t2\n", names + "INV-AC-4470 x()").single() shouldContain
            "INV-AC-4470 (task INV-T-8451): not among the ac_refs"
    }

    @Test
    fun `a missing ac_ref row and a wrong wave of this task fail naming the row`() {
        val without = table.replace("INV-AC-1572\tINV-T-8451\t2\n", "")
        AcKeyCoverage.findings(without, names.filter { !it.startsWith("INV-AC-1572") }).single() shouldContain
            "INV-AC-1572 (task INV-T-8451): an ac_ref of INV-T-8451 with no row"
        val deferred = table.replace("INV-AC-447\tINV-T-8451\t2", "INV-AC-447\tINV-T-8451\t3")
        AcKeyCoverage.findings(deferred, names).single() shouldContain "INV-AC-447 (task INV-T-8451): wave 3"
    }

    @Test
    fun `a row of another slice carrying the task key of the numbering fails naming the row`() {
        AcKeyCoverage.findings(table + "INV-AC-457\tINV-T-11349\t2\n", names + "INV-AC-457 x()").single() shouldContain
            "INV-AC-457 (task INV-T-11349): not among the ac_refs"
    }

    @Test
    fun `a look alike row of the numbering key fails naming the row`() {
        val lookAlike = "INV-AC-14360"
        val findings = AcKeyCoverage.findings(table + "$lookAlike\tINV-T-11349\t2\n", names + "$lookAlike x()")
        findings.joinToString("\n") shouldContain "$lookAlike (task INV-T-11349): not among the ac_refs"
    }

    @Test
    fun `the table without the numbering row fails naming the ac_ref`() {
        val without = table.replace("INV-AC-1436\tINV-T-11349\t2\n", "")
        AcKeyCoverage.findings(without, names.filter { !it.startsWith("INV-AC-1436") }).single() shouldContain
            "INV-AC-1436 (task INV-T-11349): an ac_ref of INV-T-11349 with no row"
    }

    @Test
    fun `the numbering row with wave 3 fails naming the row`() {
        val deferred = table.replace("INV-AC-1436\tINV-T-11349\t2", "INV-AC-1436\tINV-T-11349\t3")
        AcKeyCoverage.findings(deferred, names).single() shouldContain "INV-AC-1436 (task INV-T-11349): wave 3"
    }

    @Test
    fun `a row of another criterion carrying the task key of the intake fails naming the row`() {
        AcKeyCoverage.findings(table + "INV-AC-457\tINV-T-11286\t2\n", names + "INV-AC-457 x()").single() shouldContain
            "INV-AC-457 (task INV-T-11286): not among the ac_refs"
    }

    @Test
    fun `a look alike row of the intake key fails naming the row`() {
        val lookAlike = "INV-AC-4530"
        val findings = AcKeyCoverage.findings(table + "$lookAlike\tINV-T-11286\t2\n", names + "$lookAlike x()")
        findings.joinToString("\n") shouldContain "$lookAlike (task INV-T-11286): not among the ac_refs"
    }

    @Test
    fun `the table without the stored codes row fails naming the ac_ref`() {
        val without = table.replace("INV-AC-2779\tINV-T-11286\t2\n", "")
        AcKeyCoverage.findings(without, names.filter { !it.startsWith("INV-AC-2779") }).single() shouldContain
            "INV-AC-2779 (task INV-T-11286): an ac_ref of INV-T-11286 with no row"
    }

    @Test
    fun `the replay row with wave 3 fails naming the row`() {
        val deferred = table.replace("INV-AC-453\tINV-T-11286\t2", "INV-AC-453\tINV-T-11286\t3")
        AcKeyCoverage.findings(deferred, names).single() shouldContain "INV-AC-453 (task INV-T-11286): wave 3"
    }
}
