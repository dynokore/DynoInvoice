package com.dynokore.gst.rules.bootstrap.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The probes of the operations gate, on a planted contract, table, done set and controller. */
class OperationsTableTest {
    private val openApi =
        "paths:\n  /a:\n    post:\n      operationId: doThing\n  /b:\n    get:\n      operationId: readThing\n"
    private val asyncApi = "channels:\n  one:\n    address: 'gst.rules.one.v1'\n"
    private val header = "kind\tid\ttask\n"
    private val all =
        header + "operation\tdoThing\tINV-T-9\noperation\treadThing\tINV-T-8\nchannel\tgst.rules.one.v1\tINV-T-8\n"
    private val stubs = mapOf(ADAPTER to "class C : Api")
    private val catalogue = mapOf("rules-adapters/src/main/kotlin/Cat.kt" to "val t = \"gst.rules.one.v1\"")

    private fun run(
        table: String?,
        adapters: Map<String, String>,
        done: String? = "",
    ) = OperationsTable.check(table, openApi, asyncApi, adapters, done)

    private fun controller(body: String) =
        mapOf(ADAPTER to "class C(private val useCase: UseCase) : Api {\n  override fun doThing(x: Int): R $body\n}\n")

    @Test
    fun `a table that lists the whole contract passes`() {
        run(all, stubs).shouldBeEmpty()
    }

    @Test
    fun `an operation added to a copy of the contract is reported as missing`() {
        val bigger = openApi + "  /c:\n    put:\n      operationId: newThing\n"
        OperationsTable.check(all, bigger, asyncApi, stubs, "").single() shouldContain
            "operationId newThing is in the contract and missing"
    }

    @Test
    fun `a channel added to the contract is reported as missing`() {
        val bigger = asyncApi + "  two:\n    address: 'gst.rules.two.v1'\n"
        OperationsTable.check(all, openApi, bigger, stubs, "").single() shouldContain
            "channel gst.rules.two.v1 is in the contract and missing"
    }

    @Test
    fun `a row the contract does not hold is reported, so a typo or stale row fails`() {
        run(all + "operation\tdoThing2\tINV-T-9\n", stubs).single() shouldContain "does not hold"
        run(all.replace("doThing", "DoThing"), stubs).joinToString() shouldContain
            "operationId doThing is in the contract and missing"
    }

    @Test
    fun `a done task whose operation still answers 501 fails - no override, or a 501 body`() {
        run(all, stubs, "INV-T-9\n").single() shouldContain "still answers 501"
        run(all, controller("= ResponseEntity(HttpStatus.NOT_IMPLEMENTED)"), "INV-T-9\n").single() shouldContain
            "still answers 501"
        run(all, controller("{ return ResponseEntity.status(501).build() }"), "INV-T-9\n").single() shouldContain
            "still answers 501"
        run(all, controller("= ResponseEntity(HttpStatus.valueOf(500 + 1))"), "INV-T-9\n").single() shouldContain
            "still answers 501"
    }

    @Test
    fun `a done task whose operation is implemented passes, and a commented-out override does not count`() {
        run(all, controller("= ResponseEntity.ok(useCase.handle(x))"), "INV-T-9\n").shouldBeEmpty()
        val line = "// override fun doThing(x: Int): R = ResponseEntity.ok(useCase.handle(x))"
        run(all, mapOf(ADAPTER to "$line\nclass C"), "INV-T-9\n").single() shouldContain "still answers 501"
    }

    @Test
    fun `a done channel needs its address catalogued for emission`() {
        run(all, stubs, "INV-T-8\n").joinToString() shouldContain "channel gst.rules.one.v1 is not catalogued"
        val impl = catalogue + (ADAPTER to controller("= ResponseEntity.ok(useCase.handle(x))").getValue(ADAPTER))
        run(
            all,
            impl + mapOf("rules-adapters/src/main/kotlin/R.kt" to "override fun readThing(): R = x.read()"),
            "INV-T-8\n",
        ).shouldBeEmpty()
    }

    @Test
    fun `a missing table, a wrong header and malformed rows fail`() {
        run(null, stubs).single() shouldContain "missing"
        run("kind\tid\n", stubs).single() shouldContain "header"
        run(all + "operation\tx\tTASK\n", stubs).joinToString() shouldContain "not a task key"
        run(all + "operation\tx\tINV-T-9\tmaybe\n", stubs).joinToString() shouldContain "expected 3"
    }

    @Test
    fun `done-tasks-txt fails closed - missing, not a key, a key no row names`() {
        run(all, stubs, null).single() shouldContain "done-tasks.txt is missing"
        run(all, stubs, "T-9\n").single() shouldContain "not an INV-T key"
        run(all, stubs, "INV-T-9 \n").single() shouldContain "not an INV-T key"
        run(all, stubs, "INV-T-99\n").single() shouldContain "which no row of operations.tsv names"
        run(all, stubs, "INV-T-9x\n").single() shouldContain "not an INV-T key"
    }

    @Test
    fun `task none is only for a channel nothing publishes`() {
        run(all.replace("operation\tdoThing\tINV-T-9", "operation\tdoThing\tnone"), stubs).single() shouldContain
            "'none'"
        val none = all.replace("channel\tgst.rules.one.v1\tINV-T-8", "channel\tgst.rules.one.v1\tnone")
        run(none, stubs).shouldBeEmpty()
        run(none, catalogue).single() shouldContain "'none'"
    }

    private companion object {
        const val ADAPTER = "rules-adapters/src/main/kotlin/C.kt"
    }
}
