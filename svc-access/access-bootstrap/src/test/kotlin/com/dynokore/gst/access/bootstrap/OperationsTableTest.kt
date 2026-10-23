package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.PublishedChannels
import com.dynokore.gst.access.bootstrap.OperationsGate.Contract
import com.dynokore.gst.access.bootstrap.OperationsGate.OperationRef
import com.dynokore.gst.access.bootstrap.OperationsGate.Row
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

/** The contract gate over the real contract and the real table, and the gate's own probes (INV-T-4934). */
class OperationsTableTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val openApi = resource("gst/contracts/access/openapi.yaml")
    private val asyncApi = resource("gst/contracts/access/asyncapi.yaml")
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
        published: Set<String> = PublishedChannels.ALL,
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
            .containsExactly("channel accessProbeChannel is in the contract and missing from operations.tsv")
    }

    @Test
    fun `probe - a listed operation marked done with a 501 stub fails the gate`() {
        val done = "operation\tcreatePrincipal\tINV-T-4937\tdone"
        val marked = table.map { if (it.startsWith("operation\tcreatePrincipal\t")) done else it }
        val stub = { operation: OperationRef -> if (operation.path.endsWith("/principals")) 501 else 200 }
        val findings = verify(marked, answers = stub)
        assertThat(findings)
            .containsExactly("operation createPrincipal: marked done by INV-T-4937 but still answers 501")
    }

    /** The `task` column of the row `revokePrincipal`, compared by equality with INV-T-4937 (INV-Q-7983 A). */
    private fun revokePrincipalFinding(lines: List<String>): String? {
        val task = rows(lines).first.singleOrNull { it.kind == "operation" && it.name == "revokePrincipal" }?.task
        return if (task ==
            "INV-T-4937"
        ) {
            null
        } else {
            "operation revokePrincipal: task is ${task ?: "missing"}, not INV-T-4937"
        }
    }

    @Test
    fun `the row revokePrincipal names INV-T-4937 and the look-alike and unassigned probes fail`() {
        assertThat(revokePrincipalFinding(table)).isNull()
        val unassigned = table.map { it.replace("\trevokePrincipal\tINV-T-4937\t", "\trevokePrincipal\tUNASSIGNED\t") }
        assertThat(
            revokePrincipalFinding(unassigned),
        ).isEqualTo("operation revokePrincipal: task is UNASSIGNED, not INV-T-4937")
        val lookAlike = table.map { it.replace("\trevokePrincipal\tINV-T-4937\t", "\trevokePrincipal\tINV-T-49370\t") }
        assertThat(
            revokePrincipalFinding(lookAlike),
        ).isEqualTo("operation revokePrincipal: task is INV-T-49370, not INV-T-4937")
    }

    /** The rows INV-T-4935 names: the sole-operator pair and the three landing operations (INV-Q-7983 A). */
    private val ownedByT4935 =
        listOf(
            "recordSoleOperator",
            "withdrawSoleOperatorAcknowledgement",
            "chooseLandingScreen",
            "resolveLandingScreen",
            "clearLandingChoice",
        )

    /** The named rows whose `task` column is not INV-T-4935, compared by equality; a missing row is a finding. */
    private fun taskFindings(lines: List<String>): List<String> {
        val rows = rows(lines).first.filter { it.kind == "operation" }.associateBy { it.name }
        return ownedByT4935.mapNotNull { name ->
            val task = rows[name]?.task
            if (task == "INV-T-4935") null else "operation $name: task is ${task ?: "missing"}, not INV-T-4935"
        }
    }

    @Test
    fun `the task column of the rows INV-T-4935 names is INV-T-4935 and probes against it fail`() {
        assertThat(taskFindings(table)).isEmpty()
        val renamed =
            table.map {
                it.replace(
                    "\tclearLandingChoice\tINV-T-4935\t",
                    "\tclearLandingChoice\tINV-T-49350\t",
                )
            }
        assertThat(
            taskFindings(renamed),
        ).containsExactly("operation clearLandingChoice: task is INV-T-49350, not INV-T-4935")
        val missing = table.filterNot { it.startsWith("operation\tclearLandingChoice\t") }
        assertThat(
            taskFindings(missing),
        ).containsExactly("operation clearLandingChoice: task is missing, not INV-T-4935")
        val other = table.map { it.replace("\trecordSoleOperator\tINV-T-4935\t", "\trecordSoleOperator\tINV-T-4934\t") }
        assertThat(
            taskFindings(other),
        ).containsExactly("operation recordSoleOperator: task is INV-T-4934, not INV-T-4935")
    }

    /** The rows INV-T-4938 names: `unlockBreakGlass`, `recordBreakGlassAction` and the channel. */
    private fun taskFindingsOf4938(lines: List<String>): List<String> {
        val rows = rows(lines).first.associateBy { it.kind to it.name }
        return listOf(
            "operation" to "unlockBreakGlass",
            "operation" to "recordBreakGlassAction",
            "channel" to "accessAccessAlertRaised",
        ).mapNotNull { key ->
            val task = rows[key]?.task
            if (task ==
                "INV-T-4938"
            ) {
                null
            } else {
                "${key.first} ${key.second}: task is ${task ?: "missing"}, not INV-T-4938"
            }
        }
    }

    /** The two rows that moved to INV-T-8853 (INV-Q-8855 A): `signInBreakGlass` and `resealBreakGlass`. */
    private fun movedFindings(lines: List<String>): List<String> {
        val rows = rows(lines).first.associateBy { it.kind to it.name }
        return listOf("signInBreakGlass", "resealBreakGlass").mapNotNull { name ->
            val task = rows["operation" to name]?.task
            if (task == "INV-T-8853") null else "operation $name: task is ${task ?: "missing"}, not INV-T-8853"
        }
    }

    @Test
    fun `the rows of signInBreakGlass and resealBreakGlass name INV-T-8853 and probes against it fail`() {
        assertThat(movedFindings(table)).isEmpty()
        val signIn =
            table.map { it.replace("\tsignInBreakGlass\tINV-T-8853\t", "\tsignInBreakGlass\tINV-T-4938\t") }
        assertThat(
            movedFindings(signIn),
        ).containsExactly("operation signInBreakGlass: task is INV-T-4938, not INV-T-8853")
        val reseal =
            table.map { it.replace("\tresealBreakGlass\tINV-T-8853\t", "\tresealBreakGlass\tUNASSIGNED\t") }
        assertThat(
            movedFindings(reseal),
        ).containsExactly("operation resealBreakGlass: task is UNASSIGNED, not INV-T-8853")
        val lookAlike =
            table.map { it.replace("\tresealBreakGlass\tINV-T-8853\t", "\tresealBreakGlass\tINV-T-88530\t") }
        assertThat(movedFindings(lookAlike)).hasSize(1)
    }

    @Test
    fun `the task column of the rows INV-T-4938 names is INV-T-4938 and probes against it fail`() {
        assertThat(taskFindingsOf4938(table)).isEmpty()
        val unassigned =
            table.map {
                it.replace(
                    "\tunlockBreakGlass\tINV-T-4938\t",
                    "\tunlockBreakGlass\tUNASSIGNED\t",
                )
            }
        assertThat(taskFindingsOf4938(unassigned))
            .containsExactly("operation unlockBreakGlass: task is UNASSIGNED, not INV-T-4938")
        listOf("INV-T-4941", "INV-T-49380").forEach { other ->
            val channel =
                table.map {
                    it.replace(
                        "\taccessAccessAlertRaised\tINV-T-4938\t",
                        "\taccessAccessAlertRaised\t$other\t",
                    )
                }
            assertThat(taskFindingsOf4938(channel))
                .containsExactly("channel accessAccessAlertRaised: task is $other, not INV-T-4938")
        }
        val lookAlike =
            table.map {
                it.replace(
                    "\trecordBreakGlassAction\tINV-T-4938\t",
                    "\trecordBreakGlassAction\tINV-T-49380\t",
                )
            }
        assertThat(taskFindingsOf4938(lookAlike))
            .containsExactly("operation recordBreakGlassAction: task is INV-T-49380, not INV-T-4938")
    }

    @Test
    fun `probe - done-tasks gate fails on a missing file, a bad line, a task with no row and a 501 operation`() {
        assertThat(verifyDone(doneTasks)).isEmpty()
        assertThat(verifyDone(null)).containsExactly("done-tasks.txt is missing")
        assertThat(verifyDone(listOf("INV-T-4934", "inv-t-4934")))
            .containsExactly("done-tasks.txt: 'inv-t-4934' is not an INV-T key")
        assertThat(verifyDone(listOf("INV-T-4934 ", "INV-T-49340")))
            .contains("done-tasks.txt: 'INV-T-4934 ' is not an INV-T key")
            .contains("done-tasks.txt: INV-T-49340 names no row of operations.tsv")
        assertThat(verifyDone(listOf("INV-T-4934", "INV-T-4934")))
            .containsExactly("done-tasks.txt: INV-T-4934 is listed twice")
        assertThat(verifyDone(listOf("INV-T-4934"), answers = { if (it.path.endsWith("/authorise")) 501 else 200 }))
            .containsExactly("operation authorise: task INV-T-4934 is listed done but the operation still answers 501")
        assertThat(verifyDone(listOf("INV-T-4934"), published = emptySet()).first())
            .contains("is listed done but the code does not publish it")
    }

    @Test
    fun `probe - rows the contract does not hold, repeats, look-alikes and bad cells fail the gate`() {
        val base = table

        fun withRow(row: String) = verify(base + row)
        assertThat(withRow("operation\tgrantrole\tINV-T-4934\topen")).anyMatch { "no such operationId" in it }
        assertThat(withRow("operation\tauthorise\tINV-T-4934\tdone")).anyMatch { "listed twice" in it }
        assertThat(withRow("operation\tx\tTBD\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tunassigned\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tINV-T-12x\topen")).anyMatch { "task must be" in it }
        assertThat(withRow("operation\tx\tINV-T-1\tDONE")).anyMatch { "state must be" in it }
        assertThat(withRow("route\tx\tINV-T-1\topen")).anyMatch { "kind must be" in it }
        assertThat(withRow("operation\tx\tINV-T-1")).anyMatch { "four tab-separated" in it }
        val unassignedDone = "operation\twithdrawElevationRequest\tUNASSIGNED\tdone"
        assertThat(
            verify(base.map { it.replace("operation\twithdrawElevationRequest\tUNASSIGNED\topen", unassignedDone) }),
        ).anyMatch { "done but no task" in it }
        assertThat(verify(base.map { it.replace("\tINV-T-4934\tdone", "\tINV-T-4934\topen") }))
            .anyMatch { "published by the code but not marked done" in it }
        val unpublished = "channel\taccessMessageRequested\tINV-T-4940\tdone"
        assertThat(verify(base.map { if (it.startsWith("channel\taccessMessageRequested")) unpublished else it }))
            .containsExactly("channel accessMessageRequested: marked done but the code does not publish it")
    }

    @Test
    fun `probe - an empty table, a missing header and a missing file fail the gate`() {
        assertThat(verify(emptyList())).isNotEmpty()
        assertThat(verify(table.drop(1))).anyMatch { "header must be" in it }
        assertThat(File("../operations-missing.tsv")).doesNotExist()
    }

    /** The two operations INV-T-8853 builds: before it merges, no POST to their paths may answer 2xx (INV-Q-8855 A). */
    private fun servedFindings(answers: (OperationRef) -> Int): List<String> =
        listOf("signInBreakGlass", "resealBreakGlass").mapNotNull { name ->
            val operation = contract.operations[name] ?: return@mapNotNull "operation $name is not in the contract"
            val status = answers(operation)
            if (status in 200..299) "operation $name answers $status" else null
        }

    @Test
    fun `signInBreakGlass and resealBreakGlass answer no 2xx here and a stub that answers 200 fails the check`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val web = context.sourceApplicationContext as WebApplicationContext
            val mvc = MockMvcBuilders.webAppContextSetup(web).build()
            assertThat(servedFindings { statusOf(mvc, it) }).isEmpty()
        }
        // probes: a stub that answers 200 on either path fails, and so does one that answers 201
        assertThat(servedFindings { 200 })
            .containsExactly("operation signInBreakGlass answers 200", "operation resealBreakGlass answers 200")
        assertThat(servedFindings { if (it.path.endsWith("/reseal")) 201 else 404 })
            .containsExactly("operation resealBreakGlass answers 201")
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
            "  /internal/v1/access/probe:\n    get:\n      operationId: probeOperation\n" +
                "      responses:\n        '200':\n          description: ok\n"
        const val PROBE_CHANNEL = "  accessProbeChannel:\n    address: 'gst.access.probe.v1'\n"
    }
}
