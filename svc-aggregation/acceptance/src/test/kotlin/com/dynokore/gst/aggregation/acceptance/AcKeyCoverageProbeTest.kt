package com.dynokore.gst.aggregation.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the key gate: each is run once, with the row it must name. */
class AcKeyCoverageProbeTest {
    private val table =
        "criterion\ttask\twave\nINV-AC-447\tINV-T-8451\t2\nINV-AC-1572\tINV-T-8451\t2\n" +
            "INV-AC-1436\tINV-T-11349\t2\nINV-AC-453\tINV-T-11286\t2\nINV-AC-2779\tINV-T-11286\t2\n" +
            ""
    private val owned =
        "task\tcriterion\twave\nINV-T-8451\tINV-AC-447\t2\nINV-T-8451\tINV-AC-1572\t2\n" +
            "INV-T-11349\tINV-AC-1436\t2\nINV-T-11286\tINV-AC-453\t2\nINV-T-11286\tINV-AC-2779\t2\n"
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
        AcKeyCoverage.findings(table, owned, names).shouldBeEmpty()
    }

    @Test
    fun `deleting the test of a row fails naming the row`() {
        AcKeyCoverage.findings(table, owned, names.filter { !it.startsWith("INV-AC-447") }).single() shouldContain
            "INV-AC-447"
    }

    @Test
    fun `renaming a test to a longer look alike key fails`() {
        val findings = AcKeyCoverage.findings(table, owned, listOf("INV-AC-4470 rejects()", "INV-AC-1572 blocks()"))
        findings.joinToString("\n") shouldContain "INV-AC-447 (task INV-T-8451"
        findings.joinToString("\n") shouldContain "INV-AC-4470"
        AcKeyCoverage.findings(table, owned, listOf("INV-AC-447x rejects()", "INV-AC-1572 blocks()")).shouldNotBeEmpty()
        AcKeyCoverage.findings(table, owned, listOf("xINV-AC-447 rejects()", "INV-AC-1572 blocks()")).shouldNotBeEmpty()
    }

    @Test
    fun `a test name holding a key in no row fails`() {
        AcKeyCoverage.findings(table, owned, names + "INV-AC-999 other()").joinToString() shouldContain "INV-AC-999"
    }

    @Test
    fun `an empty table, a header only table and a missing table fail`() {
        AcKeyCoverage.findings("", owned, names).shouldNotBeEmpty()
        AcKeyCoverage.findings("criterion\ttask\twave\n", owned, names).shouldNotBeEmpty()
        AcKeyCoverage.findings(null, owned, names).single() shouldContain "missing"
    }

    @Test
    fun `a malformed or duplicate row fails and a row of another wave needs no test`() {
        AcKeyCoverage.findings(table + "INV-AC-5\tTBD\t2\n", owned, names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-447\tINV-T-2\t4\n", owned, names).shouldNotBeEmpty()
        AcKeyCoverage.findings(table + "INV-AC-7\tINV-T-2\tx\n", owned, names).shouldNotBeEmpty()
        AcKeyCoverage
            .findings(table + "INV-AC-100\tINV-T-1\t4\n", owned + "INV-T-1\tINV-AC-100\t4\n", names)
            .shouldBeEmpty()
    }

    @Test
    fun `a row of another slice carrying this task key fails naming the row`() {
        AcKeyCoverage
            .findings(
                table + "INV-AC-457\tINV-T-8451\t2\n",
                owned,
                names + "INV-AC-457 x()",
            ).single() shouldContain
            "INV-AC-457 (task INV-T-8451): not among the ac_refs"
    }

    @Test
    fun `a look alike row carrying this task key fails naming the row`() {
        AcKeyCoverage
            .findings(
                table + "INV-AC-4470\tINV-T-8451\t2\n",
                owned,
                names + "INV-AC-4470 x()",
            ).single() shouldContain
            "INV-AC-4470 (task INV-T-8451): not among the ac_refs"
    }

    @Test
    fun `a missing ac_ref row and a wrong wave of this task fail naming the row`() {
        val without = table.replace("INV-AC-1572\tINV-T-8451\t2\n", "")
        AcKeyCoverage.findings(without, owned, names.filter { !it.startsWith("INV-AC-1572") }).single() shouldContain
            "INV-AC-1572 (task INV-T-8451): an ac_ref of INV-T-8451 with no row"
        val deferred = table.replace("INV-AC-447\tINV-T-8451\t2", "INV-AC-447\tINV-T-8451\t3")
        AcKeyCoverage.findings(deferred, owned, names).single() shouldContain "INV-AC-447 (task INV-T-8451): wave 3"
    }

    @Test
    fun `a row of another slice carrying the task key of the numbering fails naming the row`() {
        AcKeyCoverage
            .findings(
                table + "INV-AC-457\tINV-T-11349\t2\n",
                owned,
                names + "INV-AC-457 x()",
            ).single() shouldContain
            "INV-AC-457 (task INV-T-11349): not among the ac_refs"
    }

    @Test
    fun `a look alike row of the numbering key fails naming the row`() {
        val lookAlike = "INV-AC-14360"
        val findings = AcKeyCoverage.findings(table + "$lookAlike\tINV-T-11349\t2\n", owned, names + "$lookAlike x()")
        findings.joinToString("\n") shouldContain "$lookAlike (task INV-T-11349): not among the ac_refs"
    }

    @Test
    fun `the table without the numbering row fails naming the ac_ref`() {
        val without = table.replace("INV-AC-1436\tINV-T-11349\t2\n", "")
        AcKeyCoverage.findings(without, owned, names.filter { !it.startsWith("INV-AC-1436") }).single() shouldContain
            "INV-AC-1436 (task INV-T-11349): an ac_ref of INV-T-11349 with no row"
    }

    @Test
    fun `the numbering row with wave 3 fails naming the row`() {
        val deferred = table.replace("INV-AC-1436\tINV-T-11349\t2", "INV-AC-1436\tINV-T-11349\t3")
        AcKeyCoverage.findings(deferred, owned, names).single() shouldContain "INV-AC-1436 (task INV-T-11349): wave 3"
    }

    @Test
    fun `a row of another criterion carrying the task key of the intake fails naming the row`() {
        AcKeyCoverage
            .findings(
                table + "INV-AC-457\tINV-T-11286\t2\n",
                owned,
                names + "INV-AC-457 x()",
            ).single() shouldContain
            "INV-AC-457 (task INV-T-11286): not among the ac_refs"
    }

    @Test
    fun `a look alike row of the intake key fails naming the row`() {
        val lookAlike = "INV-AC-4530"
        val findings = AcKeyCoverage.findings(table + "$lookAlike\tINV-T-11286\t2\n", owned, names + "$lookAlike x()")
        findings.joinToString("\n") shouldContain "$lookAlike (task INV-T-11286): not among the ac_refs"
    }

    @Test
    fun `the table without the stored codes row fails naming the ac_ref`() {
        val without = table.replace("INV-AC-2779\tINV-T-11286\t2\n", "")
        AcKeyCoverage.findings(without, owned, names.filter { !it.startsWith("INV-AC-2779") }).single() shouldContain
            "INV-AC-2779 (task INV-T-11286): an ac_ref of INV-T-11286 with no row"
    }

    @Test
    fun `the replay row with wave 3 fails naming the row`() {
        val deferred = table.replace("INV-AC-453\tINV-T-11286\t2", "INV-AC-453\tINV-T-11286\t3")
        AcKeyCoverage.findings(deferred, owned, names).single() shouldContain "INV-AC-453 (task INV-T-11286): wave 3"
    }

    private val header = "task\tcriterion\twave\n"
    private val rows = owned.removePrefix(header)

    @Test
    fun `a missing ownership file fails`() {
        AcKeyCoverage.findings(table, null, names).single() shouldBe "criteria-owned.tsv is missing"
    }

    @Test
    fun `an ownership text without the header fails and nothing else is reported`() {
        AcKeyCoverage.findings(table + "INV-AC-9\tINV-T-5\t2\n", rows, names).single() shouldBe
            "criteria-owned.tsv: the first line must be the header task, criterion, wave"
    }

    @Test
    fun `an ownership text with the header only fails`() {
        AcKeyCoverage.findings(table, header, names).single() shouldBe "criteria-owned.tsv has no rows"
    }

    @Test
    fun `a malformed ownership row fails`() {
        listOf(
            "INV-T-8451\tINV-AC-9",
            "TBD\tINV-AC-9\t2",
            "INV-T-8451\tAC-9\t2",
            "INV-T-8451\tINV-AC-9\tx",
            "INV-T-8451\tINV-AC-9\t2\textra",
        ).forEach { bad ->
            AcKeyCoverage.findings(table, owned + bad + "\n", names).single() shouldBe
                "criteria-owned.tsv: malformed row '$bad'"
        }
    }

    @Test
    fun `a repeated ownership pair fails`() {
        AcKeyCoverage.findings(table, owned + "INV-T-8451\tINV-AC-447\t2\n", names).single() shouldBe
            "criteria-owned.tsv: duplicate line for INV-T-8451 INV-AC-447"
    }

    @Test
    fun `one criterion under two tasks fails`() {
        AcKeyCoverage.findings(table, owned + "INV-T-11349\tINV-AC-447\t2\n", names).single() shouldBe
            "criteria-owned.tsv: INV-AC-447 is listed under INV-T-11349 and INV-T-8451"
    }

    @Test
    fun `a row whose task has no ownership line fails naming the row`() {
        AcKeyCoverage.findings(table + "INV-AC-100\tINV-T-1\t4\n", owned, names).single() shouldBe
            "INV-AC-100 (task INV-T-1): the task has no line in criteria-owned.tsv"
    }

    @Test
    fun `the wave of the ownership line is the wave the task runs the criterion in`() {
        val later = owned.replace("INV-T-11349\tINV-AC-1436\t2", "INV-T-11349\tINV-AC-1436\t3")
        AcKeyCoverage.findings(table, later, names).single() shouldBe
            "INV-AC-1436 (task INV-T-11349): wave 2, but INV-T-11349 runs it in wave 3"
    }

    @Test
    fun `INV-AC-447 keeps its ownership row by INV-T-8451 in the ownership file`() {
        val without = table.replace("INV-AC-447\tINV-T-8451\t2\n", "")
        AcKeyCoverage.findings(without, owned, names.filter { !it.startsWith("INV-AC-447") }).single() shouldBe
            "INV-AC-447 (task INV-T-8451): an ac_ref of INV-T-8451 with no row in ac-keys.tsv"
    }

    @Test
    fun `INV-AC-1572 keeps its ownership row by INV-T-8451 in the ownership file`() {
        val without = table.replace("INV-AC-1572\tINV-T-8451\t2\n", "")
        AcKeyCoverage.findings(without, owned, names.filter { !it.startsWith("INV-AC-1572") }).single() shouldBe
            "INV-AC-1572 (task INV-T-8451): an ac_ref of INV-T-8451 with no row in ac-keys.tsv"
    }
}
