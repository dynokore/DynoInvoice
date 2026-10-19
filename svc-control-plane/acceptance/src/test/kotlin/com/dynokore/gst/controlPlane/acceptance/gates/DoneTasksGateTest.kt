package com.dynokore.gst.controlPlane.acceptance.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DoneTasksGateTest {
    private val table =
        "kind\tid\ttask\tstate\noperation\topA\tINV-T-1\tdone\noperation\topB\tINV-T-2\tpending\nchannel\tc\tINV-T-1\tdone\n"
    private val code = OperationsGate.Code(implemented = setOf("opA"), stubbed = emptySet())

    private fun gate(
        done: String?,
        c: OperationsGate.Code = code,
    ) = DoneTasksGate.check(done, table, c).map { it.message }

    @Test
    fun `a listed task whose operations are implemented passes`() {
        gate("INV-T-1\n").shouldBeEmpty()
    }

    @Test
    fun `a missing file fails`() {
        gate(null).single() shouldBe "the file is missing"
    }

    @Test
    fun `a line that is not exactly one INV-T key fails, look-alikes included`() {
        listOf("", "INV-T-1 ", " INV-T-1", "INV-T-1x", "inv-t-1", "INV-T-", "INV-T-1,INV-T-2", "# INV-T-1", "INV-AC-1")
            .forEach { gate("INV-T-1\n$it\n").single() shouldBe "a line is exactly one INV-T-<n> key" }
    }

    @Test
    fun `a key listed twice or naming no row fails`() {
        gate("INV-T-1\nINV-T-1\n").single() shouldBe "listed twice"
        gate("INV-T-1\nINV-T-9\n").single() shouldBe "the key names no operations.tsv row"
    }

    @Test
    fun `a listed task whose operation still answers 501 fails, stub or no override`() {
        val stub = OperationsGate.Code(implemented = setOf("opA"), stubbed = setOf("opA"))
        gate("INV-T-1\n", stub).single() shouldBe "task INV-T-1 is listed done but the operation still answers 501"
        gate("INV-T-1\n", OperationsGate.Code(emptySet(), emptySet())).single() shouldBe
            "task INV-T-1 is listed done but the operation still answers 501"
    }

    @Test
    fun `an unlisted task may answer 501`() {
        gate("INV-T-1\n").shouldBeEmpty()
    }
}
