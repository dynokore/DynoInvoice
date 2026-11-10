package com.dynokore.gst.tenancy.acceptance.gates

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
          tenancyChanOne:
            address: 'gst.x.one.v1'
        components:
          messages:
            ChanOne: { payload: {} }
        """.trimIndent()
    private val header = "name\tkind\ttask\tstate\n"
    private val full =
        header + "opA\toperation\tINV-T-1\tdone\nopB\toperation\tINV-T-2\tpending\nChanOne\tchannel\tINV-T-1\tdone\n"
    private val assignedTo =
        mapOf("opA" to "INV-T-1", "opB" to "INV-T-2", "ChanOne" to "INV-T-1", "gone" to "INV-T-3", "x" to "INV-T-3")

    private fun ops(
        table: String?,
        stubs: Set<String> = emptySet(),
        implemented: Set<String> = setOf("opA"),
        spec: String = openapi,
        classes: Set<String> = setOf("ChanOnePayloadSchemaTest"),
    ) = OperationsGate
        .check(spec, asyncapi, table, OperationsGate.Code(implemented, stubs, classes)) { _, name -> assignedTo[name] }
        .map { it.message }

    @Test
    fun `a complete table passes`() {
        ops(full).shouldBeEmpty()
    }

    @Test
    fun `an operation added to the contract and missing from the table fails`() {
        val extended = "$openapi\n  /c:\n    post: { operationId: opC }"
        ops(full, spec = extended).single() shouldBe
            "the contract holds this operationId but the table does not list it"
    }

    @Test
    fun `a channel missing from the table fails`() {
        ops(header + "opA\toperation\tINV-T-1\tdone\nopB\toperation\tINV-T-2\tpending\n").single() shouldBe
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
        ops(
            full + "gone\toperation\tINV-T-3\tpending\n",
            stubs = setOf("gone"),
        ).contains("the table lists an operationId the contract does not hold") shouldBe
            true
    }

    @Test
    fun `a missing, empty or malformed table fails`() {
        ops(null).single() shouldBe "the table is missing or empty"
        ops("").single() shouldBe "the table is missing or empty"
        ops(full + "opA\toperation\tINV-T-1\tdone\n").contains("listed twice") shouldBe true
        ops(full.replace("INV-T-2", "T2")).contains("task 'T2' is not the assigned task INV-T-2") shouldBe true
        ops(full.replace("pending", "later")).contains("state 'later' is not done or pending") shouldBe true
        ops(full + "x\ttool\tINV-T-3\tdone\n").contains("kind 'tool' is not operation or channel") shouldBe true
    }

    @Test
    fun `a done channel without its payload-schema test class fails and a look-alike class does not count`() {
        for (classes in listOf(
            emptySet(),
            setOf("ChanOnePayloadSchemaTests"),
            setOf("OtherPayloadSchemaTest"),
            setOf("chanOnePayloadSchemaTest"),
        )) {
            ops(full, classes = classes).single() shouldBe
                "task INV-T-1 is done but no test class ChanOnePayloadSchemaTest exists"
        }
    }

    @Test
    fun `payload test classes are read from code, not from comments or strings`() {
        val dir =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        File(dir, "A.kt").writeText(
            "// class XPayloadSchemaTest\n/* class YPayloadSchemaTest */\nval s = \"class ZPayloadSchemaTest\"\n" +
                "internal class RealPayloadSchemaTest { }\n",
        )
        OperationsGate.classNames(listOf(File(dir, "A.kt"))) shouldBe setOf("RealPayloadSchemaTest")
    }

    private data class Ruled(
        val spec: String,
        val async: String,
        val table: String,
    )

    private fun ruled(): Ruled {
        val ops = Assignments.operations
        val channels = Assignments.channels
        val spec =
            "openapi: 3.1.0\npaths:\n" + ops.keys.joinToString("\n") { "  /$it:\n    post: { operationId: $it }" }
        val async =
            "asyncapi: 3.1.0\ncomponents:\n  messages:\n" +
                channels.keys.joinToString("\n") { "    $it: { payload: {} }" }
        val table =
            header +
                ops.entries.joinToString("") { "${it.key}\toperation\t${it.value}\tpending\n" } +
                channels.entries.joinToString("") { "${it.key}\tchannel\t${it.value}\tpending\n" }

        return Ruled(spec, async, table)
    }

    @Test
    fun `the election operations of the pinned contract are assigned to their tasks by equality`() {
        Assignments.operations["approveElectionChange"] shouldBe "INV-T-10125"
        Assignments.operations["getElectionStatus"] shouldBe "INV-T-4927"
        Assignments.operations["listElectionChanges"] shouldBe "INV-T-15888"
    }

    @Test
    fun `the ruled assignment is enforced by equality on the real names`() {
        val (spec, async, table) = ruled()

        fun run(
            t: String,
            s: String = spec,
        ) = OperationsGate.check(s, async, t, OperationsGate.Code(emptySet(), emptySet())).map {
            it.subject to
                it.message
        }

        run(table).shouldBeEmpty()
        run(
            table.replace(
                "recordMigrationCutover\toperation\tINV-T-7382",
                "recordMigrationCutover\toperation\tINV-T-73820",
            ),
        ).single()
            .first shouldBe "recordMigrationCutover"
        run(
            table.replace(
                "recordMigrationCutover\toperation\tINV-T-7382",
                "recordMigrationCutover\toperation\tunassigned",
            ),
        ).single()
            .first shouldBe "recordMigrationCutover"
        run(table.replace("answerQuestionnaire\toperation\tINV-T-4933\tpending\n", "")).single().first shouldBe
            "answerQuestionnaire"
        run(table.replace("listPeriodRecords", "listPeriodRecord")).map { it.first }.toSet() shouldBe
            setOf("listPeriodRecords", "listPeriodRecord")
        run(table + "createTenant\toperation\tINV-T-4925\tpending\n").single().second shouldBe "listed twice"
    }

    @Test
    fun `a renamed or added contract name is a finding`() {
        val (spec, async, table) = ruled()

        fun run(s: String) = OperationsGate.check(s, async, table, OperationsGate.Code(emptySet(), emptySet()))
        run(spec).shouldBeEmpty()
        run(spec.replace("moveBusinessUnit", "moveBusinessunit")).map { it.subject }.toSet() shouldBe
            setOf("moveBusinessUnit", "moveBusinessunit")
        run(spec + "\n  /extra:\n    post: { operationId: extraOp }").single().subject shouldBe "extraOp"
    }

    @Test
    fun `a done channel needs its exact payload-schema test class`() {
        val (spec, async, table) = ruled()
        val done =
            table.replace(
                "PeriodExtended\tchannel\tINV-T-4930\tpending",
                "PeriodExtended\tchannel\tINV-T-4930\tdone",
            )
        for (classes in listOf(
            emptySet(),
            setOf("PeriodExtendedPayloadSchemaTests"),
            setOf("PeriodDueDateChangedPayloadSchemaTest"),
        )) {
            OperationsGate
                .check(spec, async, done, OperationsGate.Code(emptySet(), emptySet(), classes))
                .single()
                .subject shouldBe "PeriodExtended"
        }
        OperationsGate
            .check(
                spec,
                async,
                done,
                OperationsGate.Code(emptySet(), emptySet(), setOf("PeriodExtendedPayloadSchemaTest")),
            ).shouldBeEmpty()
        OperationsGate
            .check(spec, "$async\n    Unknown: { payload: {} }", table, OperationsGate.Code(emptySet(), emptySet()))
            .single()
            .subject shouldBe "Unknown"
    }

    @Test
    fun `INV-AC-17892 the PeriodRecordsChanged channel row is required, exact and not done without its payload test`() {
        val (spec, async, table) = ruled()
        val row = "PeriodRecordsChanged\tchannel\tINV-T-16564\tpending\n"
        Assignments.channels["PeriodRecordsChanged"] shouldBe "INV-T-16564"

        fun run(
            t: String,
            classes: Set<String> = emptySet(),
        ) = OperationsGate
            .check(spec, async, t, OperationsGate.Code(emptySet(), emptySet(), classes))
            .map { it.subject to it.message }
        (row in table) shouldBe true
        run(table).shouldBeEmpty()
        run(table.replace(row, "")).single() shouldBe
            ("PeriodRecordsChanged" to "the contract holds this channel but the table does not list it")
        run(table.replace(row, row.replace("PeriodRecordsChanged", "PeriodRecordChanged")))
            .map { it.first }
            .toSet() shouldBe setOf("PeriodRecordsChanged", "PeriodRecordChanged")
        val done = table.replace(row, row.replace("pending", "done"))
        run(done).single() shouldBe
            (
                "PeriodRecordsChanged" to
                    "task INV-T-16564 is done but no test class PeriodRecordsChangedPayloadSchemaTest exists"
            )
        run(done, setOf("PeriodRecordsChangedPayloadSchemaTest")).shouldBeEmpty()
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-17922 the CertificationStatusChanged channel row is required, exact and not done without its payload test`() {
        val (spec, async, table) = ruled()
        val row = "CertificationStatusChanged\tchannel\tINV-T-16409\tpending\n"
        Assignments.channels["CertificationStatusChanged"] shouldBe "INV-T-16409"

        fun run(
            t: String,
            classes: Set<String> = emptySet(),
        ) = OperationsGate
            .check(spec, async, t, OperationsGate.Code(emptySet(), emptySet(), classes))
            .map { it.subject to it.message }
        (row in table) shouldBe true
        run(table).shouldBeEmpty()
        run(table.replace(row, "")).single() shouldBe
            ("CertificationStatusChanged" to "the contract holds this channel but the table does not list it")
        run(table.replace(row, row.replace("CertificationStatusChanged", "CertificationStatusChange")))
            .map { it.first }
            .toSet() shouldBe setOf("CertificationStatusChanged", "CertificationStatusChange")
        val done = table.replace(row, row.replace("pending", "done"))
        run(done).single() shouldBe
            (
                "CertificationStatusChanged" to
                    "task INV-T-16409 is done but no test class CertificationStatusChangedPayloadSchemaTest exists"
            )
        run(done, setOf("CertificationStatusChangedPayloadSchemaTest")).shouldBeEmpty()
    }

    @Test
    fun `a stub inside a comment or a string is not a 501 stub`() {
        val dir =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        File(
            dir,
            "A.kt",
        ).writeText("// notImplemented(\"opA\")\n/* /* nested */ notImplemented(\"opA\") */\nval s = 1\n")
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
        val findings =
            AcKeyGate.check(rows, listOf(File("T.kt") to "INV-AC-11 look-alike")).map {
                it.subject to
                    it.message
            }
        findings.contains("INV-AC-1" to "wave 2 row has no test whose name contains the key as a whole token") shouldBe
            true
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
    fun `test names are read from test source sets only, not from comments, strings or DisplayName`() {
        val root =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        File(root, "m/src/test/kotlin").mkdirs()
        File(root, "m/src/main/kotlin").mkdirs()
        File(root, "m/src/integrationTest/kotlin").mkdirs()
        File(root, "m/src/test/kotlin/A.kt").writeText(
            "// fun `INV-AC-9 commented`\n" +
                "val s = \"fun `INV-AC-7 in a string`\"\n" +
                "class A { @Test fun `INV-AC-1 real`() {} }\n",
        )
        File(root, "m/src/main/kotlin/B.kt").writeText("fun `INV-AC-8 production`() {}\n")
        File(root, "m/src/integrationTest/kotlin/C.kt").writeText("class C { @Test fun `INV-AC-2 integration`() {} }\n")
        AcKeyGate.testNames(listOf(File(root, "m"))).map { it.second }.sorted() shouldBe
            listOf("INV-AC-1 real", "INV-AC-2 integration")
    }

    @Test
    fun `INV-AC-16822 the operations table lists getCalendarPackDelta against its task and the gate fails closed`() {
        val root = File(checkNotNull(System.getProperty("gst.repo.root")) { "gst.repo.root is unset" })
        val contract = File(checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is unset" })
        val adapters = File(root, "tenancy-adapters/src/main").walkTopDown().filter { it.isFile }.toList()
        val skipped = setOf("build", ".git", ".gradle", ".gradle-cache")
        val tests =
            root
                .walkTopDown()
                .onEnter { it.name !in skipped }
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/src/test/" in it.path || "/src/integrationTest/" in it.path }
                .toList()
        val spec = File(contract, "openapi.yaml").readText()
        val async = File(contract, "asyncapi.yaml").readText()
        val table = File(root, OperationsGate.NAME).readText()
        val code =
            OperationsGate.Code(
                OperationsGate.overrides(adapters),
                OperationsGate.stubs(adapters),
                OperationsGate.classNames(tests),
            )

        fun run(t: String) = OperationsGate.check(spec, async, t, code)

        val name = "getCalendarPackDelta"
        val row = "$name\toperation\tINV-T-16829\tdone\n"
        table.split("\n").count { it == row.trimEnd() } shouldBe 1
        run(table).shouldBeEmpty()
        val probes =
            mapOf(
                "row removed" to table.replace(row, ""),
                "row twice" to table.replace(row, row + row),
                "task cell INV-T-16828" to table.replace(row, row.replace("INV-T-16829", "INV-T-16828")),
                "kind channel" to table.replace(row, row.replace("\toperation\t", "\tchannel\t")),
                "name getCalendarPackDeltas" to table.replace(row, row.replace(name, name + "s")),
                "state pending while implemented" to table.replace(row, row.replace("done", "pending")),
            )
        for ((probe, copy) in probes) {
            (copy != table) shouldBe true
            val findings = run(copy)
            check(findings.any { name in it.subject }) { "probe '$probe' gave no finding naming $name: $findings" }
        }
    }
}
