package com.dynokore.gst.submission.acceptance

import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class OperationsGateTest {
    private val contractDir = File(System.getProperty("gst.contract.dir"))
    private val openapi = File(contractDir, "openapi.yaml").readText()
    private val asyncapi = File(contractDir, "asyncapi.yaml").readText()
    private val doneText = File(System.getProperty("gst.root.dir"), "done-tasks.txt").takeIf { it.isFile }?.readText()
    private val tsv = File(System.getProperty("gst.root.dir"), "operations.tsv").readText()

    /** Operations answering 501 (OperationsStubs): what each controller method does for placeholder arguments. */
    private val stubs: Set<String> =
        OperationsStubs.stubs(listOf(ReadinessController::class.java, TransactionsController::class.java))

    private fun run(
        table: String = tsv,
        spec: String = openapi,
        done: String? = "",
        stubSet: Set<String> = stubs,
    ) = OperationsGate.findings(table, spec, asyncapi, stubSet, done)

    @Test
    fun `the real table covers the real contract`() {
        OperationsGate.contractOperations(openapi).size shouldBe 16
        OperationsGate.contractOperations(openapi) shouldContain "approveFilingGateOverride"
        OperationsGate.contractOperations(openapi) shouldContain "getFilingGateOverrides"
        OperationsGate.contractChannels(asyncapi).size shouldBe 6
        run(done = doneText).shouldBeEmpty()
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

    @Test
    fun `a lower case unassigned and TBD fail`() {
        run(table = tsv.replaceFirst("UNASSIGNED", "unassigned")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("UNASSIGNED", "TBD")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("UNASSIGNED", "INV-T-12x")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("UNASSIGNED", "")).shouldNotBeEmpty()
    }

    @Test
    fun `a listed operation marked done with a 501 stub fails`() {
        val table = tsv.replaceFirst("getReadiness\tUNASSIGNED", "getReadiness\tINV-T-9999")
        run(table = table).shouldBeEmpty()
        run(table = table, done = "INV-T-9999\n") shouldContain
            "operation getReadiness: task INV-T-9999 is done but the operation still answers 501"
        run(table = table, done = "INV-T-9999\n", stubSet = stubs - "getReadiness").shouldBeEmpty()
    }

    @Test
    fun `a channel missing from the table, a duplicate row and an unknown row fail`() {
        run(table = tsv.lines().filterNot { it.contains("example-happened") }.joinToString("\n")).shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tgetReadiness\tUNASSIGNED\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tnoSuchOperation\tUNASSIGNED\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\nqueue\tx\tUNASSIGNED\n").shouldNotBeEmpty()
    }

    @Test
    fun `the done set fails on a missing file, a non key line and a key naming no row`() {
        run(done = null) shouldContain "done-tasks.txt: the file is missing"
        run(done = "TBD\n") shouldContain "done-tasks.txt: 'TBD' is not exactly an INV-T key"
        run(done = "inv-t-5964\n") shouldContain "done-tasks.txt: 'inv-t-5964' is not exactly an INV-T key"
        val table = tsv.replaceFirst("getReadiness\tUNASSIGNED", "getReadiness\tINV-T-5964")
        run(table = table, done = "INV-T-59640\n") shouldContain
            "done-tasks.txt: INV-T-59640 names no row of operations.tsv"
        run(table = table, done = "INV-T-5964 \n").shouldNotBeEmpty()
    }

    @Test
    fun `a listed key whose operation is implemented passes`() {
        val table = tsv.replaceFirst("getReadiness\tUNASSIGNED", "getReadiness\tINV-T-5964")
        run(table = table, done = "INV-T-5964\n", stubSet = stubs - "getReadiness").shouldBeEmpty()
    }
}
