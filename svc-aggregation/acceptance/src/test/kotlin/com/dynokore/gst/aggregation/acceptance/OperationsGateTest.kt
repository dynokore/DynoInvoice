package com.dynokore.gst.aggregation.acceptance

import com.dynokore.gst.aggregation.adapters.http.DrillDownController
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import io.kotest.matchers.string.shouldContain as stringContain

class OperationsGateTest {
    private val contractDir = File(System.getProperty("gst.contract.dir"))
    private val openapi = File(contractDir, "openapi.yaml").readText()
    private val asyncapi = File(contractDir, "asyncapi.yaml").readText()
    private val doneText = File(System.getProperty("gst.root.dir"), "done-tasks.txt").takeIf { it.isFile }?.readText()
    private val tsv = File(System.getProperty("gst.root.dir"), "operations.tsv").readText()

    /** Operations answering 501 (OperationsStubs): what each controller method does for placeholder arguments. */
    private val stubs: Set<String> =
        OperationsStubs.stubs(listOf(IntakeController::class.java, DrillDownController::class.java))

    private fun run(
        table: String = tsv,
        spec: String = openapi,
        done: String? = "",
        stubSet: Set<String> = stubs,
    ) = OperationsGate.findings(table, spec, asyncapi, stubSet, done)

    @Test
    fun `the real table covers the real contract`() {
        OperationsGate.contractOperations(openapi).size shouldBe 2
        OperationsGate.contractChannels(asyncapi).size shouldBe 3
        run(done = doneText).shouldBeEmpty()
        OperationsGate.assignmentFindings(tsv).shouldBeEmpty()
        doneText shouldBe ""
    }

    @Test
    fun `every operation of the real contract is a stub until a task overrides it`() {
        stubs shouldBe OperationsGate.contractOperations(openapi)
    }

    @Test
    fun `an operation added to a copy of the contract is missing from the table`() {
        val extra =
            openapi.replaceFirst(
                "paths:",
                "paths:\n  /v1/probe:\n    get:\n      operationId: probeOperation\n" +
                    "      responses:\n        '200':\n          description: ok",
            )
        run(spec = extra) shouldContain "operation probeOperation: in the contract, missing from operations.tsv"
    }

    private val placeholder = "channel\taggregationExampleHappened\tPLACEHOLDER\topen"
    private val withoutPlaceholder =
        tsv
            .lines()
            .filterNot {
                it.contains(
                    "aggregationExampleHappened",
                )
            }.joinToString("\n")

    private fun withRow(row: String) = withoutPlaceholder.trimEnd() + "\n" + row + "\n"

    @Test
    fun `the placeholder row passes and every look-alike of it fails naming the row`() {
        tsv.lines() shouldContain placeholder
        run(table = withRow(placeholder)).shouldBeEmpty()
        listOf(
            "channel\taggregationAggregateReady\tPLACEHOLDER\topen",
            "operation\tpublishExampleHappened\tPLACEHOLDER\topen",
            "channel\taggregationexamplehappened\tPLACEHOLDER\topen",
            "channel\taggregationExampleHappened2\tPLACEHOLDER\topen",
            "channel\taggregationExampleHappened\tplaceholder\topen",
            "channel\taggregationExampleHappened\tPLACEHOLDER\tdone",
            "channel\taggregationExampleHappened\tUNASSIGNED\topen",
            "channel\taggregationExampleHappened\tINV-T-8451\topen",
            "channel\taggregationExampleHappened\tPLACEHOLDER \topen",
        ).forEach { row ->
            val table =
                if (row.startsWith("channel\taggregationAggregateReady")) {
                    tsv.replaceFirst("channel\taggregationAggregateReady\tINV-T-4979\topen", row) +
                        withRow(placeholder).lines().last { it.isNotBlank() } +
                        "\n"
                } else {
                    withRow(row)
                }
            run(table = table).shouldNotBeEmpty()
        }
    }

    @Test
    fun `UNASSIGNED, TBD, an empty task and a bad state fail on any row`() {
        listOf("UNASSIGNED", "unassigned", "TBD", "INV-T-12x", "").forEach {
            run(table = tsv.replaceFirst("INV-T-4980", it)).shouldNotBeEmpty()
        }
        run(table = tsv.replaceFirst("INV-T-4980\topen", "INV-T-4980\tdone ")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("INV-T-4980\topen", "INV-T-4980\t")).shouldNotBeEmpty()
    }

    @Test
    fun `a listed operation marked done with a 501 stub fails`() {
        val table =
            tsv.replaceFirst(
                "getAggregateDrillDown\tINV-T-4980\topen",
                "getAggregateDrillDown\tINV-T-9999\topen",
            )
        run(table = table).shouldBeEmpty()
        run(table = table, done = "INV-T-9999\n") shouldContain
            "operation getAggregateDrillDown: task INV-T-9999 is done but the operation still answers 501"
        run(table = table, done = "INV-T-9999\n", stubSet = stubs - "getAggregateDrillDown").shouldBeEmpty()
    }

    @Test
    fun `a channel missing from the table, a duplicate row and an unknown row fail`() {
        run(
            table = tsv.lines().filterNot { it.contains("aggregationExampleHappened") }.joinToString("\n"),
        ).shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tgetAggregateDrillDown\tINV-T-4980\topen\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tnoSuchOperation\tINV-T-1\topen\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\nqueue\tx\tINV-T-1\topen\n").shouldNotBeEmpty()
    }

    @Test
    fun `the done set fails on a missing file, a non key line and a key naming no row`() {
        run(done = null) shouldContain "done-tasks.txt: the file is missing"
        run(done = "TBD\n") shouldContain "done-tasks.txt: 'TBD' is not exactly an INV-T key"
        run(done = "inv-t-8451\n") shouldContain "done-tasks.txt: 'inv-t-8451' is not exactly an INV-T key"
        val table =
            tsv.replaceFirst(
                "getAggregateDrillDown\tINV-T-4980\topen",
                "getAggregateDrillDown\tINV-T-9998\topen",
            )
        run(table = table, done = "INV-T-99980\n") shouldContain
            "done-tasks.txt: INV-T-99980 names no row of operations.tsv"
        run(table = table, done = "INV-T-9998 \n").shouldNotBeEmpty()
    }

    @Test
    fun `the table names the task the split sets for each row`() {
        val intake = "intakeAggregationBatch\tINV-T-8473\topen"
        OperationsGate
            .assignmentFindings(
                tsv.replaceFirst(intake, "intakeAggregationBatch\tINV-T-4979\topen"),
            ).single() stringContain
            "operation intakeAggregationBatch: task 'INV-T-4979', the split sets INV-T-8473"
        OperationsGate
            .assignmentFindings(
                tsv.replaceFirst(intake, "intakeAggregationBatch\tINV-T-84730\topen"),
            ).single() stringContain
            "intakeAggregationBatch"
        OperationsGate.assignmentFindings(tsv.replace("\tINV-T-4979\t", "\tINV-T-49790\t")).single() stringContain
            "channel aggregationAggregateReady: task 'INV-T-49790'"
        OperationsGate.assignmentFindings(tsv.replace("\tINV-T-8453\t", "\tUNASSIGNED\t")).single() stringContain
            "aggregationAggregateWindowChanged"
        OperationsGate
            .assignmentFindings(
                tsv.lines().filterNot { it.contains("getAggregateDrillDown") }.joinToString("\n"),
            ).single() stringContain "operation getAggregateDrillDown: no row"
    }

    @Test
    fun `this slice owns no row, so its key is not in the done set`() {
        // INV-T-8451 names no row of the table: listing it in done-tasks.txt fails the gate naming the key
        run(done = "INV-T-8451\n") shouldContain "done-tasks.txt: INV-T-8451 names no row of operations.tsv"
        tsv.lines().none { it.endsWith("\tINV-T-8451") } shouldBe true
    }

    @Test
    fun `a listed key whose operation is implemented passes`() {
        val table =
            tsv.replaceFirst(
                "getAggregateDrillDown\tINV-T-4980\topen",
                "getAggregateDrillDown\tINV-T-9998\topen",
            )
        run(table = table, done = "INV-T-9998\n", stubSet = stubs - "getAggregateDrillDown").shouldBeEmpty()
    }
}
