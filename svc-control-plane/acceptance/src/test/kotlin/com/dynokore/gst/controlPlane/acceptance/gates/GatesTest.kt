package com.dynokore.gst.controlPlane.acceptance.gates

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class GatesTest {
    private val openapi =
        """
        openapi: 3.1.0
        paths:
          /a:
            post: { operationId: opA }
          /b/{x}:
            get: { operationId: opB }
        """.trimIndent()
    private val asyncapi =
        """
        asyncapi: 3.1.0
        channels:
          chanOne:
            address: 'gst.x.one.v1'
        """.trimIndent()
    private val header = "kind\tid\ttask\tstate\n"
    private val full = header + "operation\topA\tINV-T-1\tdone\noperation\topB\tINV-T-2\tpending\nchannel\tchanOne\tINV-T-1\tdone\n"

    private fun ops(
        table: String?,
        stubs: Set<String> = emptySet(),
        implemented: Set<String> = setOf("opA"),
        spec: String = openapi,
    ) = OperationsGate.check(spec, asyncapi, table, implemented, stubs).map { it.message }

    @Test
    fun `a complete table passes`() {
        ops(full).shouldBeEmpty()
    }

    @Test
    fun `an operation added to the contract and missing from the table fails`() {
        val extended = "$openapi\n  /c:\n    post: { operationId: opC }"
        ops(full, spec = extended).single() shouldBe "the contract holds this operationId but the table does not list it"
    }

    @Test
    fun `a channel missing from the table fails`() {
        ops(header + "operation\topA\tINV-T-1\tdone\noperation\topB\tINV-T-2\tpending\n").single() shouldBe
            "the contract holds this channel but the table does not list it"
    }

    @Test
    fun `a done operation that still answers 501 fails`() {
        ops(full, implemented = setOf("opA"), stubs = setOf("opA")).single() shouldBe
            "task INV-T-1 is done but the operation still answers 501"
    }

    @Test
    fun `a done operation that no class overrides answers the generated 501`() {
        ops(full, implemented = emptySet()).single() shouldBe "task INV-T-1 is done but the operation still answers 501"
    }

    @Test
    fun `a pending operation that is implemented is a stale row`() {
        ops(full, implemented = setOf("opA", "opB")).single() shouldBe
            "task INV-T-2 is pending but the operation is implemented (stale row)"
    }

    @Test
    fun `a row for an operation the contract does not hold fails`() {
        ops(full + "operation\tgone\tINV-T-3\tdone\n").single() shouldBe "the table lists an operationId the contract does not hold"
    }

    @Test
    fun `a missing, empty or malformed table fails`() {
        ops(null).single() shouldBe "the table is missing or empty"
        ops("").single() shouldBe "the table is missing or empty"
        ops(full + "operation\topA\tINV-T-1\tdone\n").map { it }.contains("listed twice") shouldBe true
        ops(full.replace("INV-T-2", "T2")).contains("task 'T2' is not an INV-T-<n> key") shouldBe true
        ops(full.replace("pending", "later")).contains("state 'later' is not done or pending") shouldBe true
        ops(full + "tool\tx\tINV-T-3\tdone\n").contains("kind 'tool' is not operation or channel") shouldBe true
    }

    @Test
    fun `a stub inside a comment or a string is not a 501 stub`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        File(dir, "A.kt").writeText("// notImplemented(\"opA\")\n/* /* nested */ notImplemented(\"opA\") */\nval s = 1\n")
        OperationsGate.stubs(listOf(File(dir, "A.kt"))) shouldBe emptySet()
        File(dir, "B.kt").writeText("fun f() = notImplemented(\"opA\")\n")
        OperationsGate.stubs(listOf(File(dir, "B.kt"))) shouldBe setOf("opA")
    }

    private val rows = "criterion\ttask\twave\nINV-AC-1\tINV-T-1\t2\nINV-AC-2\tINV-T-1\t3\n"
    private val named = listOf(File("T.kt") to "INV-AC-1 a thing works")

    @Test
    fun `a table whose wave 2 rows each have a test passes`() {
        AcKeyGate.check(rows, named).shouldBeEmpty()
    }

    @Test
    fun `a missing, empty or header-only table fails`() {
        AcKeyGate.check(null, named).single().message shouldBe "the table is missing or empty"
        AcKeyGate.check("", named).single().message shouldBe "the table is missing or empty"
        AcKeyGate.check("criterion\ttask\twave\n", named).single().message shouldBe "the table has no row"
    }

    @Test
    fun `a wave 2 row without a test fails naming the row`() {
        AcKeyGate.check(rows, emptyList()).single().let {
            it.subject shouldBe "INV-AC-1"
            it.message shouldBe "wave 2 row has no test whose name contains the key as a whole token"
        }
    }

    @Test
    fun `a longer look-alike key does not satisfy the row and is itself a finding`() {
        val findings = AcKeyGate.check(rows, listOf(File("T.kt") to "INV-AC-11 look-alike")).map { it.subject to it.message }
        findings.contains("INV-AC-1" to "wave 2 row has no test whose name contains the key as a whole token") shouldBe true
        findings.any { it.first == "INV-AC-11" && it.second.endsWith("names a key that is in no row") } shouldBe true
    }

    @Test
    fun `whole token boundaries`() {
        AcKeyGate.holdsKey("INV-AC-1 x", "INV-AC-1") shouldBe true
        AcKeyGate.holdsKey("x INV-AC-1", "INV-AC-1") shouldBe true
        AcKeyGate.holdsKey("INV-AC-12", "INV-AC-1") shouldBe false
        AcKeyGate.holdsKey("INV-AC-1-2", "INV-AC-1") shouldBe false
        AcKeyGate.holdsKey("XINV-AC-1", "INV-AC-1") shouldBe false
        AcKeyGate.holdsKey("inv-ac-1", "INV-AC-1") shouldBe false
    }

    @Test
    fun `a test of a deferred row is allowed and a test naming a key outside the table fails`() {
        AcKeyGate.check(rows, named + (File("U.kt") to "INV-AC-2 deferred part")).shouldBeEmpty()
        AcKeyGate.check(rows, named + (File("U.kt") to "INV-AC-3 stray")).single().subject shouldBe "INV-AC-3"
    }

    @Test
    fun `test names are read from test source sets only and not from comments`() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        File(root, "m/src/test/kotlin").mkdirs()
        File(root, "m/src/main/kotlin").mkdirs()
        File(root, "m/src/integrationTest/kotlin").mkdirs()
        File(root, "m/src/test/kotlin/A.kt").writeText("// fun `INV-AC-9 commented`\nclass A { @Test fun `INV-AC-1 real`() {} }\n")
        File(root, "m/src/main/kotlin/B.kt").writeText("fun `INV-AC-8 production`() {}\n")
        File(root, "m/src/integrationTest/kotlin/C.kt").writeText("@DisplayName(\"INV-AC-2 display\") class C\n")
        AcKeyGate.testNames(listOf(File(root, "m"))).map { it.second }.sorted() shouldBe listOf("INV-AC-1 real", "INV-AC-2 display")
    }
}
