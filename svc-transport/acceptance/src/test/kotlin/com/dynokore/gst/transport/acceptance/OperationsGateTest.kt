package com.dynokore.gst.transport.acceptance

import com.dynokore.gst.transport.adapters.http.AdministrationController
import com.dynokore.gst.transport.adapters.http.DespatchController
import com.dynokore.gst.transport.adapters.http.NotificationsController
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
        OperationsStubs.stubs(
            listOf(
                DespatchController::class.java,
                NotificationsController::class.java,
                AdministrationController::class.java,
            ),
        )

    /** The done text, the stub set and the task cell of the row receiveApNotification that belong together. */
    private data class GateState(
        val done: String,
        val stubSet: Set<String>,
        val cell: String,
    )

    /** Parallel change (INV-Q-15626 A): exactly the three states: before INV-T-12486, after it and after INV-T-4982. */
    private val state1 =
        GateState(
            done = "INV-T-4981\n",
            stubSet = OperationsGate.contractOperations(openapi) - "submitPayload",
            cell = "INV-T-4982",
        )
    private val state2 =
        GateState(
            done = "INV-T-4981\nINV-T-12486\n",
            stubSet = OperationsGate.contractOperations(openapi) - "submitPayload" - "receiveApNotification",
            cell = "INV-T-12486",
        )
    private val state3 =
        GateState(
            done = "INV-T-4981\nINV-T-12486\nINV-T-4982\n",
            stubSet =
                OperationsGate.contractOperations(openapi) - "submitPayload" - "receiveApNotification" -
                    "electAcknowledgementRoute" - "clearAcknowledgementRoute",
            cell = "INV-T-12486",
        )
    private val allStates = listOf(state1, state2, state3)

    /** The state selected by the done text alone (equality), or null. */
    private fun stateByDone(done: String?): GateState? = allStates.firstOrNull { it.done == done }

    /** The state of the triple, or null: the done text selects it, then the stub set and the cell must be equal. */
    private fun stateOf(
        done: String?,
        stubSet: Set<String>,
        cell: String?,
    ): GateState? = stateByDone(done)?.takeIf { it.stubSet == stubSet && it.cell == cell }

    /** The third field of the one line whose first two fields equal operation and receiveApNotification. */
    private fun cellOf(table: String): String? =
        table
            .lines()
            .map { it.split("\t") }
            .singleOrNull { it.size >= 3 && it[0] == "operation" && it[1] == "receiveApNotification" }
            ?.get(2)

    private val cell = cellOf(tsv)

    private fun run(
        table: String = tsv,
        spec: String = openapi,
        done: String? = "",
        stubSet: Set<String> = stubs,
    ) = OperationsGate.findings(table, spec, asyncapi, stubSet, done)

    @Test
    fun `the real table covers the real contract`() {
        OperationsGate.contractOperations(openapi).size shouldBe 10
        OperationsGate.contractOperations(openapi) shouldContain "electAcknowledgementRoute"
        OperationsGate.contractOperations(openapi) shouldContain "clearAcknowledgementRoute"
        OperationsGate.contractChannels(asyncapi).size shouldBe 4
        run(done = doneText).shouldBeEmpty()
        stateOf(doneText, stubs, cell).shouldNotBeNull()
    }

    @Test
    fun `every operation of the real contract is a stub until a task overrides it`() {
        // state 1: only submitPayload is live; state 2 adds receiveApNotification (INV-T-12486); state 3 adds
        // electAcknowledgementRoute and clearAcknowledgementRoute (INV-T-4982)
        stubs shouldBe stateByDone(doneText)!!.stubSet
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
        run(table = tsv.replaceFirst("INV-T-4982", "unassigned")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("INV-T-4982", "TBD")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("INV-T-4982", "INV-T-12x")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("INV-T-4982", "")).shouldNotBeEmpty()
    }

    @Test
    fun `UNASSIGNED is allowed only for the three ids of INV-Q-8045`() {
        run(table = tsv.replaceFirst("setConnectionEntities\tINV-T-4983", "setConnectionEntities\tUNASSIGNED"))
            .shouldNotBeEmpty()
        run(table = tsv.replaceFirst("submitPayload\tINV-T-4981", "submitPayload\tUNASSIGNED")).shouldNotBeEmpty()
        run(
            table =
                tsv.replaceFirst(
                    "access-point-route-changed.v1\tINV-T-4982",
                    "access-point-route-changed.v1\tUNASSIGNED",
                ),
        ).shouldNotBeEmpty()
        // a look-alike id is not one of the three, even though the rest of the row is unchanged
        run(table = tsv.replaceFirst("submitPackage\t", "submitPackages\t")).shouldNotBeEmpty()
        run(table = tsv.replaceFirst("sendNetworkDocument\t", "SendNetworkDocument\t")).shouldNotBeEmpty()
        // the same id under the other kind is not allowed either
        run(table = tsv.replaceFirst("operation\tsubmitPackage", "channel\tsubmitPackage")).shouldNotBeEmpty()
    }

    @Test
    fun `the task of this service whose operation still answers 501 is not in the done set`() {
        // INV-T-4982 owns receiveApNotification: listing the task while the operation answers 501 fails the gate
        run(done = "INV-T-4982\n", stubSet = stubs + "electAcknowledgementRoute" + "clearAcknowledgementRoute") shouldContain
            "operation electAcknowledgementRoute: task INV-T-4982 is done but the operation still answers 501"
        run(done = "INV-T-4982\n", stubSet = stubs + "clearAcknowledgementRoute" + "clearAcknowledgementRoute") shouldContain
            "operation clearAcknowledgementRoute: task INV-T-4982 is done but the operation still answers 501"
        run(done = "$cell\n", stubSet = stubs + "receiveApNotification") shouldContain
            "operation receiveApNotification: task $cell is done but the operation still answers 501"
        // INV-T-4981 has overridden submitPayload: its listing passes
        run(done = "INV-T-4981\n").shouldBeEmpty()
    }

    @Test
    fun `a listed operation marked done with a 501 stub fails`() {
        val table = tsv.replaceFirst("receiveApNotification\t$cell", "receiveApNotification\tINV-T-9999")
        (table != tsv) shouldBe true
        run(table = table).shouldBeEmpty()
        run(table = table, done = "INV-T-9999\n", stubSet = stubs + "receiveApNotification") shouldContain
            "operation receiveApNotification: task INV-T-9999 is done but the operation still answers 501"
        run(table = table, done = "INV-T-9999\n", stubSet = stubs - "receiveApNotification").shouldBeEmpty()
    }

    @Test
    fun `a channel missing from the table, a duplicate row and an unknown row fail`() {
        run(table = tsv.lines().filterNot { it.contains("example-happened") }.joinToString("\n")).shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tsubmitPayload\tUNASSIGNED\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\noperation\tnoSuchOperation\tUNASSIGNED\n").shouldNotBeEmpty()
        run(table = tsv.trimEnd() + "\nqueue\tx\tUNASSIGNED\n").shouldNotBeEmpty()
    }

    @Test
    fun `the done set fails on a missing file, a non key line and a key naming no row`() {
        run(done = null) shouldContain "done-tasks.txt: the file is missing"
        run(done = "TBD\n") shouldContain "done-tasks.txt: 'TBD' is not exactly an INV-T key"
        run(done = "inv-t-5964\n") shouldContain "done-tasks.txt: 'inv-t-5964' is not exactly an INV-T key"
        val table = tsv
        run(table = table, done = "INV-T-49810\n") shouldContain
            "done-tasks.txt: INV-T-49810 names no row of operations.tsv"
        run(table = table, done = "INV-T-4981 \n").shouldNotBeEmpty()
    }

    @Test
    fun `a listed key whose operation is implemented passes`() {
        run(table = tsv, done = "INV-T-4981\n", stubSet = stubs).shouldBeEmpty()
        run(table = tsv, done = "INV-T-4981\n", stubSet = stubs + "submitPayload") shouldContain
            "operation submitPayload: task INV-T-4981 is done but the operation still answers 501"
    }

    @Test
    fun `the state function returns state 1 for the state 1 triple`() {
        stateOf(state1.done, state1.stubSet, state1.cell) shouldBe state1
    }

    @Test
    fun `the state function returns state 2 for the state 2 triple`() {
        stateOf(state2.done, state2.stubSet, state2.cell) shouldBe state2
    }

    @Test
    fun `the state function returns null for the state 2 done text with the state 1 stub set`() {
        stateOf(state2.done, state1.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for the state 1 done text with a stub set without receiveApNotification`() {
        stateOf(state1.done, state1.stubSet - "receiveApNotification", state1.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for the state 2 triple with the cell of state 1`() {
        stateOf(state2.done, state2.stubSet, "INV-T-4982").shouldBeNull()
    }

    @Test
    fun `the state function returns null for the state 1 triple with the cell of state 2`() {
        stateOf(state1.done, state1.stubSet, "INV-T-12486").shouldBeNull()
    }

    @Test
    fun `the state function returns null for the state 2 done text plus a line INV-T-4982`() {
        stateOf(state2.done + "INV-T-4982\n", state2.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for the done text INV-T-124860`() {
        stateOf("INV-T-124860\n", state2.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for the done text INV-T-12486 with CR LF`() {
        stateOf("INV-T-12486\r\n", state2.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for a done text with a leading space before INV-T-12486`() {
        stateOf("INV-T-4981\n INV-T-12486\n", state2.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for a null done text`() {
        stateOf(null, state1.stubSet, state1.cell).shouldBeNull()
    }

    @Test
    fun `the state function returns null for a null cell`() {
        stateOf(state1.done, state1.stubSet, null).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns state 3 for the state 3 triple`() {
        stateOf(state3.done, state3.stubSet, state3.cell) shouldBe state3
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 3 done text with the state 2 stub set`() {
        stateOf(state3.done, state2.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for state 3 with electAcknowledgementRoute still a stub`() {
        stateOf(state3.done, state3.stubSet + "electAcknowledgementRoute", state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for state 3 with clearAcknowledgementRoute still a stub`() {
        stateOf(state3.done, state3.stubSet + "clearAcknowledgementRoute", state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 2 done text with the state 3 stub set`() {
        stateOf(state2.done, state3.stubSet, state2.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 3 triple with the cell INV-T-4982`() {
        stateOf(state3.done, state3.stubSet, "INV-T-4982").shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the done lines in the order 4981 4982 12486`() {
        stateOf("INV-T-4981\nINV-T-4982\nINV-T-12486\n", state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for a state 3 done text ending with INV-T-49820`() {
        stateOf("INV-T-4981\nINV-T-12486\nINV-T-49820\n", state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for a state 3 done text with CR LF after INV-T-4982`() {
        stateOf("INV-T-4981\nINV-T-12486\nINV-T-4982\r\n", state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for a state 3 done text with a leading space before INV-T-4982`() {
        stateOf("INV-T-4981\nINV-T-12486\n INV-T-4982\n", state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 3 done text plus a line INV-T-4983`() {
        stateOf(state3.done + "INV-T-4983\n", state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for a null done text with the state 3 stub set`() {
        stateOf(null, state3.stubSet, state3.cell).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 3 triple with a null cell`() {
        stateOf(state3.done, state3.stubSet, null).shouldBeNull()
    }

    @Test
    fun `INV-AC-18428 the state function returns null for the state 3 done text with submitPayload added to its stub set`() {
        // the exact state 3 set already lacks submitPayload (live since INV-T-4981): adding it is a look-alike
        stateOf(state3.done, state3.stubSet + "submitPayload", state3.cell).shouldBeNull()
        // and a set lacking any other operation is a look-alike too
        stateOf(state3.done, state3.stubSet - "setConnectionEntities", state3.cell).shouldBeNull()
    }

    @Test
    fun `the cell is read from the one operation row by field equality`() {
        cellOf("operation\treceiveApNotification\tINV-T-4982\n") shouldBe "INV-T-4982"
        cellOf("operation\treceiveApNotifications\tINV-T-4982\n").shouldBeNull()
        cellOf("channel\treceiveApNotification\tINV-T-4982\n").shouldBeNull()
        cellOf("operation\treceiveApNotification\tA\noperation\treceiveApNotification\tB\n").shouldBeNull()
    }
}
