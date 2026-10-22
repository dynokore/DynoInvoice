package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.events.PublishedChannels
import com.dynokore.gst.masterdata.bootstrap.OperationsGate.Contract
import com.dynokore.gst.masterdata.bootstrap.OperationsGate.OperationRef
import com.dynokore.gst.masterdata.bootstrap.OperationsGate.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.UUID

/** The contract gate over the real contract and the real table, and the gate's own probes (INV-T-4943). */
class OperationsTableTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val openApi = resource("gst/contracts/masterdata/openapi.yaml")
    private val asyncApi = resource("gst/contracts/masterdata/asyncapi.yaml")
    private val contract = OperationsGate.contractOf(openApi, asyncApi)
    private val tableFile = File("../operations.tsv")

    private fun rows(lines: List<String>): Pair<List<Row>, List<String>> {
        val findings = mutableListOf<String>()
        return OperationsGate.parse(lines, findings) to findings
    }

    private fun verify(
        lines: List<String>,
        contract: Contract = this.contract,
        answers: (OperationRef) -> Int = { 200 },
        published: Set<String> = PublishedChannels.ALL,
    ): List<String> {
        val (rows, findings) = rows(lines)
        return OperationsGate.verify(rows, contract, findings, published, answers)
    }

    private val table: List<String> get() = tableFile.readLines()
    private val doneFile = File("../done-tasks.txt")
    private val doneTasks: List<String>? get() = if (doneFile.exists()) doneFile.readLines() else null

    private fun verifyDone(
        done: List<String>?,
        lines: List<String> = table,
        answers: (OperationRef) -> Int = { 200 },
        published: Set<String> = contract.channels,
    ): List<String> = OperationsGate.verifyDoneTasks(done, rows(lines).first, contract, published, answers)

    @Test
    fun `the real table lists every operationId and channel of the contract and no done operation answers 501`() {
        assertThat(tableFile).exists()
        val findings = mutableListOf<String>()
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val web = context.sourceApplicationContext as WebApplicationContext
            val mvc = MockMvcBuilders.webAppContextSetup(web).build()
            val (rows, parseFindings) = rows(table)
            findings +=
                OperationsGate.verify(rows, contract, parseFindings, PublishedChannels.ALL) { statusOf(mvc, it) }
            findings +=
                OperationsGate.verifyDoneTasks(doneTasks, rows, contract, PublishedChannels.ALL) { statusOf(mvc, it) }
        }
        assertThat(findings).isEmpty()
    }

    /** The assignments the task text names (ruling INV-Q-7890 A), compared with the table's task column by equality. */
    private val assigned =
        mapOf(
            "operation" to "resolveMasterDataGap" to "INV-T-4943",
            "operation" to "resolveCounterparty" to "INV-T-4943",
            "operation" to "getCounterpartyHistory" to "INV-T-4943",
            "operation" to "getImportRecord" to "INV-T-4944",
            "operation" to "getRegisterAndRates" to "INV-T-4947",
            "channel" to "masterdataMasterDataResolved" to "INV-T-4943",
            "operation" to "confirmIdentityTripleChange" to "INV-T-4942",
            "channel" to "masterdataExampleHappened" to "PLACEHOLDER",
        )

    private fun assignmentFindings(lines: List<String>): List<String> {
        val rows = rows(lines).first
        return assigned
            .map { (key, task) ->
                val row = rows.singleOrNull { it.kind == key.first && it.name == key.second }
                when {
                    row == null -> "${key.first} ${key.second}: no row, the task text assigns $task"
                    row.task != task -> "${key.first} ${key.second}: task is ${row.task}, the task text assigns $task"
                    else -> null
                }
            }.filterNotNull()
    }

    @Test
    fun `the named rows carry the tasks the task text assigns, and look-alikes or a missing row fail`() {
        assertThat(assignmentFindings(table)).isEmpty()

        fun swap(
            from: String,
            to: String,
        ) = table.map {
            if (it.startsWith(
                    from,
                )
            ) {
                it.replace(Regex("""\t(INV-T-\d+|PLACEHOLDER)\t"""), "\t$to\t")
            } else {
                it
            }
        }
        assertThat(assignmentFindings(swap("operation\tgetImportRecord\t", "INV-T-4943")))
            .containsExactly("operation getImportRecord: task is INV-T-4943, the task text assigns INV-T-4944")
        assertThat(assignmentFindings(swap("operation\tgetImportRecord\t", "INV-T-49440")))
            .containsExactly("operation getImportRecord: task is INV-T-49440, the task text assigns INV-T-4944")
        assertThat(assignmentFindings(table.filterNot { it.startsWith("operation\tconfirmIdentityTripleChange\t") }))
            .containsExactly("operation confirmIdentityTripleChange: no row, the task text assigns INV-T-4942")
        assertThat(assignmentFindings(swap("channel\tmasterdataExampleHappened\t", "INV-T-4942")))
            .containsExactly("channel masterdataExampleHappened: task is INV-T-4942, the task text assigns PLACEHOLDER")
    }

    @Test
    fun `probe - an operation added to a copy of the contract and missing from the table fails the gate`() {
        val extended =
            openApi.replace(
                "paths:\n",
                "paths:\n" + PROBE_PATH,
            )
        val findings = verify(table, OperationsGate.contractOf(extended, asyncApi))
        assertThat(findings)
            .containsExactly("operation probeOperation is in the contract and missing from operations.tsv")
    }

    @Test
    fun `probe - a channel added to a copy of the contract and missing from the table fails the gate`() {
        val extended = asyncApi.replace("channels:\n", "channels:\n" + PROBE_CHANNEL)
        val findings = verify(table, OperationsGate.contractOf(openApi, extended))
        assertThat(findings)
            .containsExactly("channel masterdataProbeChannel is in the contract and missing from operations.tsv")
    }

    @Test
    fun `probe - a listed operation marked done with a 501 stub fails the gate`() {
        val done = "operation\tresolveCounterparty\tINV-T-4943\tdone"
        val marked = table.map { if (it.startsWith("operation\tresolveCounterparty\t")) done else it }
        val stub = { operation: OperationRef ->
            if (operation.path.endsWith("/counterparties/resolution")) 501 else 200
        }
        val findings = verify(marked, answers = stub)
        assertThat(findings)
            .containsExactly("operation resolveCounterparty: marked done by INV-T-4943 but still answers 501")
    }

    @Test
    fun `probe - done-tasks gate fails on a missing file, a bad line, a task with no row and a 501 operation`() {
        assertThat(verifyDone(doneTasks)).isEmpty()
        assertThat(verifyDone(null)).containsExactly("done-tasks.txt is missing")
        assertThat(verifyDone(listOf("INV-T-4943", "inv-t-4943")))
            .containsExactly("done-tasks.txt: 'inv-t-4943' is not an INV-T key")
        assertThat(verifyDone(listOf("INV-T-4943 ", "INV-T-49430")))
            .contains("done-tasks.txt: 'INV-T-4943 ' is not an INV-T key")
            .contains("done-tasks.txt: INV-T-49430 names no row of operations.tsv")
        assertThat(verifyDone(listOf("INV-T-4943", "INV-T-4943")))
            .containsExactly("done-tasks.txt: INV-T-4943 is listed twice")
        assertThat(
            verifyDone(listOf("INV-T-4943"), answers = {
                if (it.path.endsWith("/counterparties/resolution")) 501 else 200
            }),
        ).containsExactly(
            "operation resolveCounterparty: task INV-T-4943 is listed done but the operation still answers 501",
        )
        assertThat(verifyDone(listOf("INV-T-4943"), published = emptySet()).first())
            .contains("is listed done but the code does not publish it")
    }

    @Test
    fun `probe - rows the contract does not hold, repeats, look-alikes and bad cells fail the gate`() {
        val base = table

        fun withRow(row: String) = verify(base + row)
        assertThat(withRow("operation\tresolvecounterparty\tINV-T-4943\topen")).anyMatch { "no such operationId" in it }
        assertThat(withRow("operation\tresolveCounterparty\tINV-T-4943\topen")).anyMatch { "listed twice" in it }
        assertThat(withRow("operation\tx\tTBD\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tunassigned\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tINV-T-12x\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tINV-T-1\tDONE")).anyMatch { "state must be" in it }
        assertThat(withRow("route\tx\tINV-T-1\topen")).anyMatch { "kind must be" in it }
        assertThat(withRow("operation\tx\tINV-T-1")).anyMatch { "four tab-separated" in it }
        assertThat(withRow("operation\tx\tUNASSIGNED\topen")).anyMatch { "task must be" in it }
        assertThat(verify(base, published = setOf("masterdataMasterDataResolved")))
            .containsExactly("channel masterdataMasterDataResolved: published by the code but not marked done")
        val unpublished = "channel\tmasterdataMasterDataResolved\tINV-T-4943\tdone"
        assertThat(verify(base.map { if (it.startsWith("channel\tmasterdataMasterDataResolved")) unpublished else it }))
            .containsExactly("channel masterdataMasterDataResolved: marked done but the code does not publish it")
    }

    @Test
    fun `probe - PLACEHOLDER is accepted for exactly the channel masterdataExampleHappened`() {
        val real = "channel\tmasterdataExampleHappened\tPLACEHOLDER\topen"
        val base = table
        assertThat(base).contains(real)
        assertThat(verify(base)).isEmpty()

        fun replaced(row: String) = verify(base.map { if (it == real) row else it })
        assertThat(replaced("channel\tmasterdataReferenceRateLoaded\tPLACEHOLDER\topen"))
            .anyMatch { "masterdataReferenceRateLoaded: PLACEHOLDER is accepted only for" in it }
        assertThat(verify(base + "operation\tgetImportRecord\tPLACEHOLDER\topen"))
            .anyMatch { "operation getImportRecord: PLACEHOLDER is accepted only for" in it }
        val relabelled =
            base.map {
                if (it.startsWith(
                        "operation\tgetImportRecord\t",
                    )
                ) {
                    "operation\tgetImportRecord\tPLACEHOLDER\topen"
                } else {
                    it
                }
            }
        assertThat(verify(relabelled)).anyMatch { "operation getImportRecord: PLACEHOLDER is accepted only for" in it }
        assertThat(replaced("channel\tmasterdataexamplehappened\tPLACEHOLDER\topen"))
            .anyMatch { "masterdataexamplehappened" in it }
        val renamed = replaced("channel\tmasterdataExampleHappened2\tPLACEHOLDER\topen")
        assertThat(renamed)
            .anyMatch { "channel masterdataExampleHappened2: the contract holds no such channel" in it }
            .anyMatch { "channel masterdataExampleHappened is in the contract and missing" in it }
        assertThat(replaced("channel\tmasterdataExampleHappened\tplaceholder\topen"))
            .anyMatch { "masterdataExampleHappened: the task must be exactly PLACEHOLDER" in it }
        assertThat(replaced("channel\tmasterdataExampleHappened\tPLACEHOLDER\tdone"))
            .anyMatch { "the PLACEHOLDER row must be open" in it }
        assertThat(replaced("channel\tmasterdataExampleHappened\tUNASSIGNED\topen"))
            .anyMatch { "task must be INV-T-<digits>" in it }
            .anyMatch { "the task must be exactly PLACEHOLDER" in it }
        assertThat(replaced("channel\tmasterdataExampleHappened\tINV-T-4947\topen"))
            .containsExactly("channel masterdataExampleHappened: the task must be exactly PLACEHOLDER")
        assertThat(verify(base, published = PublishedChannels.ALL + "masterdataExampleHappened"))
            .containsExactly("channel masterdataExampleHappened: the code must not publish the PLACEHOLDER channel")
    }

    @Test
    fun `the placeholder set of the event catalogue equals the channel the gate accepts as PLACEHOLDER`() {
        val field = MasterdataEventCatalogue::class.java.getDeclaredField("PLACEHOLDER_CHANNELS")
        field.isAccessible = true
        val holder = MasterdataEventCatalogue::class.java.getDeclaredField("INSTANCE").get(null)
        // a private val of an object is a static field of the object's class in Kotlin
        val value =
            field.get(
                if (java.lang.reflect.Modifier
                        .isStatic(field.modifiers)
                ) {
                    null
                } else {
                    holder
                },
            )
        assertThat(value).isEqualTo(setOf(OperationsGate.PLACEHOLDER_NAME))
    }

    @Test
    fun `probe - an empty table, a missing header and a missing file fail the gate`() {
        assertThat(verify(emptyList())).isNotEmpty()
        assertThat(verify(table.drop(1))).anyMatch { "header must be" in it }
        assertThat(File("../operations-missing.tsv")).doesNotExist()
    }

    private fun statusOf(
        mvc: MockMvc,
        operation: OperationRef,
    ): Int {
        var path = operation.path
        val parameters = (operation.raw["parameters"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        parameters.filter { it["in"] == "path" }.forEach { path = path.replace("{" + it["name"] + "}", "PROBE-1") }
        val method =
            org.springframework.http.HttpMethod
                .valueOf(operation.method)
        val request = MockMvcRequestBuilders.request(method, path)
        parameters.filter { it["in"] == "query" && it["required"] == true }.forEach {
            request.queryParam(it["name"].toString(), "probe")
        }
        val needsKey = parameters.any { (it["\$ref"] as? String)?.endsWith("IdempotencyKey") == true }
        if (needsKey) {
            request.header("Idempotency-Key", UUID.randomUUID().toString())
        }
        if (operation.raw["requestBody"] != null) {
            val body = JsonMapper().writeValueAsString(exampleBody(operation))
            request.contentType(MediaType.APPLICATION_JSON).content(body)
        }
        return try {
            mvc
                .perform(request)
                .andReturn()
                .response.status
        } catch (e: Exception) {
            // the handler ran and failed (nothing is bound to a bare request): it is not the 501 stub
            if (e.cause?.javaClass?.simpleName == "NotImplementedException") STUB else 500
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun exampleBody(operation: OperationRef): Any {
        val body = operation.raw["requestBody"] as Map<String, Any?>
        val json = (body["content"] as Map<String, Map<String, Any?>>)["application/json"].orEmpty()
        val examples = json["examples"] as? Map<String, Map<String, Any?>>
        return examples?.values?.firstOrNull()?.get("value") ?: emptyMap<String, Any>()
    }

    private companion object {
        const val STUB = OperationsGate.STUB_STATUS
        const val PROBE_PATH =
            "  /internal/v1/masterdata/probe:\n    get:\n      operationId: probeOperation\n" +
                "      responses:\n        '200':\n          description: ok\n"
        const val PROBE_CHANNEL = "  masterdataProbeChannel:\n    address: 'gst.masterdata.probe.v1'\n"
    }
}
