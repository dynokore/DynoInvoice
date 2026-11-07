package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.gapreport.SweepStaleRecords
import com.dynokore.gst.masterdata.application.imports.ImportRecordBytes
import com.dynokore.gst.masterdata.application.imports.ReadImportRecord
import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * The counterparty import (INV-T-4944) at the service boundary: dry run, import, import record, evidence, long-command
 * answers and the sweep, over the real context and a real PostgreSQL.
 */
@Suppress("LargeClass") // one boundary class for the import operations, as GapReportTest is for the report
class ImportTest {
    private val json = JsonMapper.builder().build()
    private val prefix = "/internal/v1/masterdata"

    private data class Answer(
        val status: Int,
        val body: String,
        val location: String?,
    )

    private fun RegistryEnv.call(
        method: String,
        path: String,
        actor: String = OPERATOR,
        body: String? = null,
    ): Answer =
        ce.inTenant(actor) {
            val mvc: MockMvc =
                MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()
            val request =
                if (method == "POST") {
                    MockMvcRequestBuilders
                        .post(path)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body ?: "{}")
                } else {
                    MockMvcRequestBuilders.get(path)
                }
            val response = mvc.perform(request).andReturn().response
            Answer(response.status, response.contentAsString, response.getHeader("Location"))
        }

    private fun hash(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun row(
        ref: String?,
        uen: String?,
        name: String? = "ACME",
        line1: String? = "1 Raffles Place",
        postal: String? = "048616",
    ): String {
        val node = json.createObjectNode()
        ref?.let { node.put("counterpartyRef", it) }
        uen?.let { node.put("uen", it) }
        name?.let { node.put("legalName", it) }
        line1?.let { node.put("addressLine1", it) }
        postal?.let { node.put("postalCode", it) }
        return node.toString()
    }

    private fun dryBody(
        fileHash: String,
        rows: List<String>,
        rowCount: Int = rows.size,
    ) = """{"fileHash":"$fileHash","rowCount":$rowCount,"rows":[${rows.joinToString(",")}]}"""

    private fun RegistryEnv.dryRun(
        fileHash: String,
        rows: List<String>,
    ): Pair<Answer, JsonNode> {
        val answer = call("POST", "$prefix/counterparties/import/dry-runs", body = dryBody(fileHash, rows))
        return answer to json.readTree(answer.body)
    }

    private fun importBody(
        entity: String?,
        dryRunId: String?,
        fileHash: String,
        rowCount: Int,
        providedBy: String? = "MIGRATED",
    ): String {
        val node = json.createObjectNode()
        entity?.let { node.put("entityRef", it) }
        dryRunId?.let { node.put("dryRunId", it) }
        node.put("fileHash", fileHash)
        node.put("rowCount", rowCount)
        providedBy?.let { node.put("providedBy", it) }
        return node.toString()
    }

    private fun RegistryEnv.fingerprint(): String =
        BoundaryFixture.asSuperuser { c ->
            val tables =
                listOf(
                    "counterparty",
                    "counterparty_version",
                    "counterparty_identifier",
                    "counterparty_scheme_status",
                    "counterparty_import",
                )
            tables.joinToString("|") { table ->
                c
                    .createStatement()
                    .executeQuery(
                        "select count(*) || ':' || coalesce(md5(string_agg(t::text, '|' order by t::text)), '') " +
                            "from masterdata.$table t where tenant_id = '${ce.tenant}'::uuid",
                    ).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            }
        }

    private fun RegistryEnv.awaitRecord(location: String): JsonNode {
        repeat(POLLS) {
            val status = json.readTree(call("GET", location).body)
            if (status.get("state").asString() != "RUNNING") return status
            Thread.sleep(POLL_MILLIS)
        }
        error("the record did not finish")
    }

    private fun RegistryEnv.applyImport(
        entity: String,
        dryRunId: String,
        h: String,
    ) = call("POST", "$prefix/counterparties/import", body = importBody(entity, dryRunId, h, 1))

    /** Rows of [table] of this tenant only (the database is shared by the other test classes). */
    private fun RegistryEnv.n(
        table: String,
        where: String = "true",
    ): Int = ce.count("select count(*) from masterdata.$table where tenant_id = '${ce.tenant}'::uuid and $where")

    private fun uen(n: Int) = "%08d".format(java.util.Locale.ROOT, n) + "X"

    @Test
    fun `INV-AC-357 a 5000 row file with 40 invalid UENs and 12 duplicates lists 52 rows and writes nothing`() =
        runRegistry { env ->
            val tag = UUID.randomUUID().toString().take(8)
            val rows = mutableListOf<String>()
            repeat(5000) { i ->
                val ref = "C$tag-$i"
                rows +=
                    when {
                        i < 40 -> row(ref, "5301234X")
                        i in 40 until 46 -> row(ref, uen(900000 + i - 40))
                        i in 46 until 52 -> row(ref, uen(900000 + i - 46))
                        else -> row(ref, uen(i))
                    }
            }
            val before = env.fingerprint()
            val (answer, result) = env.dryRun(hash("f357"), rows)
            assertThat(answer.status).isEqualTo(200)
            assertThat(env.fingerprint()).isEqualTo(before)
            val rejected = result.get("rejectedRows").values()
            val duplicateRows = result.get("duplicates").values().sumOf { it.get("rows").size() }
            assertThat(rejected.size).isEqualTo(40)
            assertThat(rejected.map { it.get("code").asString() }.toSet()).isEqualTo(setOf("MDM-003"))
            assertThat(rejected.map { it.get("field").asString() }.toSet()).isEqualTo(setOf("/uen"))
            assertThat(duplicateRows).isEqualTo(12)
            assertThat(rejected.size + duplicateRows).isEqualTo(52)
            assertThat(result.get("validCount").asInt()).isEqualTo(4948)
            assertThat(result.get("rowCount").asInt()).isEqualTo(5000)
            assertThat(result.get("fileHash").asString()).isEqualTo(hash("f357"))
        }

    @Test
    fun `INV-AC-357 a dry run without rows or with a wrong rowCount is refused 400 and over 5000 rows 413`() =
        runRegistry { env ->
            val before = env.n("counterparty_import_dry_run")
            val path = "$prefix/counterparties/import/dry-runs"
            val h = hash("x")
            listOf(
                """{"fileHash":"$h","rowCount":0}""",
                dryBody(h, listOf(row("A", uen(1))), rowCount = 2),
            ).forEach {
                val answer = env.call("POST", path, body = it)
                assertThat(answer.status).isEqualTo(400)
                assertThat(answer.body).contains("SRT-001")
            }
            val big = env.call("POST", path, body = dryBody(h, List(5001) { row("C$it", uen(it)) }))
            assertThat(big.status).isEqualTo(413)
            assertThat(big.body).contains("SRT-014")
            assertThat(env.n("counterparty_import_dry_run")).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9601 an import naming no usable dry run is refused 422 MDM-009 and writes nothing`() =
        runRegistry { env ->
            val entity = env.entity()
            val h1 = hash("h1")
            val (_, d1) = env.dryRun(h1, listOf(row(unique("CTP"), uen(11))))
            val d1Id = d1.get("dryRunId").asString()
            val (_, d2) = env.dryRun(hash("h2"), listOf(row(unique("CTP"), uen(12))))
            val running =
                env
                    .dryRun(hash("h3"), listOf(row(unique("CTP"), uen(13))))
                    .second
                    .get("dryRunId")
                    .asString()
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().executeUpdate(
                    "update masterdata.counterparty_import_dry_run set state = 'RUNNING', completed_at = null, " +
                        "result = null where dry_run_id = '$running'",
                )
            }
            val before = env.fingerprint()
            val attempts =
                listOf(
                    importBody(entity, null, h1, 1),
                    importBody(entity, "DRY-unknown", h1, 1),
                    importBody(entity, d2.get("dryRunId").asString(), h1, 1),
                    importBody(entity, running, hash("h3"), 1),
                )
            attempts.forEach {
                val answer = env.call("POST", "$prefix/counterparties/import", body = it)
                assertThat(answer.status).isEqualTo(422)
                assertThat(json.readTree(answer.body).get("code").asString()).isEqualTo("MDM-009")
            }
            assertThat(env.fingerprint()).isEqualTo(before)
            assertThat(d1Id).startsWith("DRY-")
        }

    @Test
    @Suppress("LongMethod") // one scenario: apply twice, then the rows, events and provenance
    fun `INV-AC-9601 INV-AC-357 a reviewed dry run applies once, MIGRATED, and publishes the event`() =
        runRegistry { env ->
            val entity = env.entity()
            val h = hash("apply")
            val a = unique("CTP")
            val b = unique("CTP")
            val (_, dry) = env.dryRun(h, listOf(row(a, uen(21)), row(b, uen(22))))
            val dryRunId = dry.get("dryRunId").asString()
            val events = env.ce.captured.envelopes.size
            val body = importBody(entity, dryRunId, h, 2, "MIGRATED")
            val first = env.call("POST", "$prefix/counterparties/import", body = body)
            assertThat(first.status).isEqualTo(201)
            val importId = json.readTree(first.body).get("importId").asString()
            assertThat(json.readTree(first.body).get("appliedCount").asInt()).isEqualTo(2)
            val again = env.call("POST", "$prefix/counterparties/import", body = body)
            assertThat(again.status).isEqualTo(201)
            assertThat(json.readTree(again.body).get("importId").asString()).isEqualTo(importId)
            assertThat(env.n("counterparty_import")).isEqualTo(1)
            assertThat(env.n("counterparty_version")).isEqualTo(2)
            val resolved =
                env.ce.captured.envelopes
                    .drop(events)
                    .filter { it.type.contains("MasterDataResolved") }
            assertThat(resolved.map { it.subject }.toSet()).isEqualTo(setOf(a, b))
            assertThat(resolved.map { it.entity.value }.toSet()).isEqualTo(setOf(entity))
            assertThat(env.n("counterparty_version", "import_id = '$importId'"))
                .isEqualTo(2)
            assertThat(
                env.n("counterparty_version", "provided_by = 'MIGRATED'"),
            ).isEqualTo(2)
            val written = json.readTree(env.call("GET", "$prefix/counterparties/$a/history").body)
            assertThat(
                written
                    .get("items")
                    .get(0)
                    .get("counterparty")
                    .get("providedBy")
                    .asString(),
            ).isEqualTo("MIGRATED")
            assertThat(
                written
                    .get("items")
                    .get(0)
                    .get("counterparty")
                    .get("importId")
                    .asString(),
            ).isEqualTo(importId)
        }

    @Test
    fun `INV-AC-9601 an import without providedBy or entityRef is refused 400 and writes nothing`() =
        runRegistry { env ->
            val entity = env.entity()
            val h = hash("p")
            val dryRunId =
                env
                    .dryRun(h, listOf(row(unique("CTP"), uen(31))))
                    .second
                    .get("dryRunId")
                    .asString()
            val before = env.fingerprint()
            listOf(
                importBody(entity, dryRunId, h, 1, null),
                importBody(entity, dryRunId, h, 1, "COUNTERPARTY"),
                importBody(null, dryRunId, h, 1),
                importBody(" ", dryRunId, h, 1),
            ).forEach {
                val answer = env.call("POST", "$prefix/counterparties/import", body = it)
                assertThat(answer.status).isEqualTo(400)
                assertThat(answer.body).contains("SRT-001")
            }
            assertThat(env.fingerprint()).isEqualTo(before)
        }

    @Test
    @Suppress("LongMethod") // one scenario: import, record, permission and evidence
    fun `INV-AC-1882 INV-AC-9603 the import record holds hash actor counts and rejected rows and is published`() =
        runRegistry { env ->
            val entity = env.entity()
            val h = hash("rec")
            val rows =
                List(240) { i ->
                    if (i < 2) {
                        row(unique("CTP"), uen(500 + i), postal = null)
                    } else {
                        row(unique("CTP"), uen(500 + i))
                    }
                }
            val dryRunId =
                env
                    .dryRun(h, rows)
                    .second
                    .get("dryRunId")
                    .asString()
            val events = env.ce.captured.envelopes.size
            val answer = env.call("POST", "$prefix/counterparties/import", body = importBody(entity, dryRunId, h, 240))
            val result =
                if (answer.status == 201) {
                    json.readTree(answer.body)
                } else {
                    env.awaitRecord(checkNotNull(answer.location)).get("result")
                }
            val importId = result.get("importId").asString()
            assertThat(result.get("appliedCount").asInt()).isEqualTo(238)
            assertThat(result.get("failedCount").asInt()).isEqualTo(2)
            val record = json.readTree(env.call("GET", "$prefix/imports/$importId").body)
            assertThat(record.get("actorRef").asString()).isEqualTo(OPERATOR)
            assertThat(record.get("fileHash").asString()).isEqualTo(h)
            assertThat(record.get("dryRunId").asString()).isEqualTo(dryRunId)
            assertThat(record.get("state").asString()).isEqualTo("SUCCEEDED")
            assertThat(record.get("rejectedRows").values().map { it.get("row").asInt() to it.get("code").asString() })
                .isEqualTo(listOf(1 to "MDM-006", 2 to "MDM-006"))
            val other = env.call("GET", "$prefix/imports/$importId", actor = TestPermissions.OTHER_OPERATOR)
            assertThat(other.status).isEqualTo(404)
            assertThat(other.body).doesNotContain(h)
            assertThat(env.call("GET", "$prefix/imports/$importId", actor = "IDN-NOBODY").status).isEqualTo(403)
            val stored =
                env.ce.inTenant {
                    env.context.getBean(ReadImportRecord::class.java).handle(importId, OPERATOR)
                }
            val expected = ImportRecordBytes.sha256(ImportRecordBytes.of(checkNotNull(stored)))
            assertThat(stored.recordHash).isEqualTo(expected)
            val evidence =
                env.ce.captured.envelopes
                    .drop(events)
                    .filter { it.type.contains("EvidenceArtefactRecorded") }
            assertThat(evidence).hasSize(1)
            val payload = json.readTree(evidence.single().dataJson)
            assertThat(payload.get("artefactType").asString()).isEqualTo("IMPORT")
            assertThat(payload.get("reference").asString()).isEqualTo(importId)
            assertThat(payload.get("hash").asString()).isEqualTo(expected).matches("[0-9a-f]{64}")
        }

    @Test
    fun `INV-AC-9601 a person without the import permission is refused 403 SRT-004 and nothing is stored`() =
        runRegistry { env ->
            val entity = env.entity()
            val before = env.fingerprint()
            val runs = env.n("counterparty_import_dry_run")
            val nobody = "IDN-NOBODY"
            val dry = env.call("POST", "$prefix/counterparties/import/dry-runs", nobody, dryBody(hash("n"), listOf()))
            val imp = env.call("POST", "$prefix/counterparties/import", nobody, importBody(entity, "D", hash("n"), 0))
            assertThat(listOf(dry.status, imp.status)).isEqualTo(listOf(403, 403))
            assertThat(dry.body).contains("SRT-004")
            assertThat(env.fingerprint()).isEqualTo(before)
            assertThat(env.n("counterparty_import_dry_run")).isEqualTo(runs)
        }

    @Test
    fun `INV-AC-9601 an apply delayed past x-timeout answers 202 and its record later reads SUCCEEDED`() =
        runRegistry { env ->
            val entity = env.entity()
            val h = hash("late")
            val dryRunId =
                env
                    .dryRun(h, listOf(row(unique("CTP"), uen(41))))
                    .second
                    .get("dryRunId")
                    .asString()
            ImportFault.delayMillis[entity] = LATE_MILLIS * 3
            try {
                val answer = env.applyImport(entity, dryRunId, h)
                assertThat(answer.status).isEqualTo(202)
                val location = checkNotNull(answer.location)
                assertThat(location).startsWith("$prefix/imports/IMP-")
                assertThat(json.readTree(env.call("GET", location).body).get("state").asString()).isEqualTo("RUNNING")
                val repeat = env.applyImport(entity, dryRunId, h)
                assertThat(repeat.status).isEqualTo(202)
                val done = env.awaitRecord(location)
                assertThat(done.get("state").asString()).isEqualTo("SUCCEEDED")
                assertThat(done.get("result").get("appliedCount").asInt()).isEqualTo(1)
                assertThat(env.n("counterparty_version")).isEqualTo(1)
            } finally {
                ImportFault.delayMillis.remove(entity)
                ImportFault.failing -= entity
            }
        }

    @Test
    fun `INV-AC-9601 an apply that throws leaves no master data and the record FAILED`() =
        runRegistry { env ->
            val entity = env.entity()
            val h = hash("boom")
            val dryRunId =
                env
                    .dryRun(h, listOf(row(unique("CTP"), uen(51))))
                    .second
                    .get("dryRunId")
                    .asString()
            val before = env.n("counterparty_version")
            val events = env.n("outbox")
            ImportFault.failing += entity
            try {
                val answer = env.applyImport(entity, dryRunId, h)
                assertThat(answer.status).isEqualTo(500)
            } finally {
                ImportFault.delayMillis.remove(entity)
                ImportFault.failing -= entity
            }
            assertThat(env.n("counterparty_version")).isEqualTo(before)
            assertThat(env.n("outbox")).isEqualTo(events)
            assertThat(env.n("counterparty_import", "state = 'FAILED'"))
                .isEqualTo(1)
        }

    @Test
    fun `INV-AC-9601 the sweep finalises a RUNNING record older than the setting and leaves a younger one`() =
        runRegistry { env ->
            val old = "DRY-old-" + UUID.randomUUID().toString().take(8)
            val young = "DRY-new-" + UUID.randomUUID().toString().take(8)

            fun insert(
                id: String,
                ageMinutes: Long,
            ) = BoundaryFixture.asSuperuser { c ->
                c.createStatement().executeUpdate(
                    "insert into masterdata.counterparty_import_dry_run (id, tenant_id, dry_run_id, actor_ref, " +
                        "file_hash, row_count, rows, state, started_at, created_at, updated_at, version) values " +
                        "(gen_random_uuid(), '${env.ce.tenant}'::uuid, '$id', 'P', '${hash(id)}', 0, '[]', " +
                        "'RUNNING', now() - interval '$ageMinutes minutes', now(), now(), 0)",
                )
            }
            insert(old, SWEEP_OLD_MINUTES)
            insert(young, 1)
            val swept = env.ce.inTenant { env.context.getBean(SweepStaleRecords::class.java).run() }
            assertThat(swept).isEqualTo(1)

            fun state(id: String) =
                BoundaryFixture.asSuperuser { c ->
                    val sql = "select state from masterdata.counterparty_import_dry_run where dry_run_id = '$id'"
                    c.createStatement().executeQuery(sql).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            assertThat(listOf(state(old), state(young))).isEqualTo(listOf("FAILED", "RUNNING"))
        }

    @Test
    fun `INV-AC-9601 a start without masterdata long-command max-run fails`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("masterdata.long-command.max-run=\${masterdata.absent.value}")
            .run { context -> assertThat(context).hasFailed() }
    }

    private fun parseCsv(text: String): List<Map<String, String>> {
        val lines = text.trimEnd().split("\r\n")
        val header = lines.first().split(",")
        return lines.drop(1).map { line ->
            val cells = mutableListOf<String>()
            var current = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        current.append('"')
                        i++
                    }

                    ch == '"' -> {
                        quoted = !quoted
                    }

                    ch == ',' && !quoted -> {
                        cells += current.toString()
                        current = StringBuilder()
                    }

                    else -> {
                        current.append(ch)
                    }
                }
                i++
            }
            cells += current.toString()
            header.zip(cells).toMap()
        }
    }

    private class Loop(
        val entity: String,
        val absent: String,
        val invalid: String,
        val incomplete: String,
    )

    private fun RegistryEnv.seedLoop(): Loop {
        val loop = Loop(entity(), unique("CTP-A"), unique("CTP-B"), unique("CTP-C"))
        val short = CounterpartyAddress(addressLine1 = "1 Road", postalCode = " ")
        val peppol = "PEPPOL_PARTICIPANT_ID" to "0195:X"
        ce.write(loop.absent, ce.request(peppol, name = "ACME", address = ADDRESS))
        ce.write(loop.invalid, ce.request("UEN" to "5301234X", name = "ACME", address = ADDRESS))
        ce.write(loop.incomplete, ce.request("UEN" to "201912345A", name = "ACME", address = short))
        return loop
    }

    /** The codes of the gap report of [loop] by counterparty, and its export file parsed by the test helper. */
    private fun RegistryEnv.reportAndExport(loop: Loop): Pair<Map<String, String>, List<Map<String, String>>> {
        val run = call("POST", "$prefix/gap-reports", body = """{"entityRef":"${loop.entity}"}""")
        val report = json.readTree(run.body)
        val codes =
            report.get("rows").values().associate { r ->
                r.get("counterpartyRef").asString() to r.get("errorCode").asString()
            }
        val id =
            BoundaryFixture.asSuperuser { c ->
                val sql = "select gap_report_id from masterdata.gap_report where tenant_id = '${ce.tenant}'::uuid"
                c.createStatement().executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        return codes to parseCsv(call("GET", "$prefix/gap-reports/$id/export").body)
    }

    private fun fileRow(
        cells: Map<String, String>,
        uen: String? = cells["uen"],
        postal: String? = cells["postalCode"],
    ) = row(cells["counterpartyRef"], uen?.ifEmpty { null }, cells["legalName"], cells["addressLine1"], postal)

    @Test
    fun `INV-AC-9602 INV-AC-1881 an unremediated export is rejected with the report codes, a completed one applies`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val loop = env.seedLoop()
                val (reportCodes, export) = env.reportAndExport(loop)
                val before = env.fingerprint()
                val (answer, dry) = env.dryRun(hash("loop"), export.map { fileRow(it) })
                assertThat(answer.status).isEqualTo(200)
                assertThat(env.fingerprint()).isEqualTo(before)
                val rejected =
                    dry.get("rejectedRows").values().associate { r ->
                        export[r.get("row").asInt() - 1]["counterpartyRef"] to r.get("code").asString()
                    }
                val expected = mapOf(loop.absent to "MDM-002", loop.invalid to "MDM-003", loop.incomplete to "MDM-006")
                assertThat(rejected).isEqualTo(expected)
                assertThat(rejected).isEqualTo(reportCodes)
                val completed =
                    export.map { cells ->
                        when (cells["counterpartyRef"]) {
                            loop.absent -> fileRow(cells, uen = uen(700))
                            loop.invalid -> fileRow(cells, uen = uen(701))
                            else -> fileRow(cells, postal = "048616")
                        }
                    }
                val (_, dry2) = env.dryRun(hash("loop2"), completed)
                assertThat(dry2.get("rejectedRows").size()).isEqualTo(0)
                assertThat(dry2.get("duplicates").size()).isEqualTo(0)
                val body = importBody(loop.entity, dry2.get("dryRunId").asString(), hash("loop2"), 3, "INTERNAL")
                val applied = env.call("POST", "$prefix/counterparties/import", body = body)
                assertThat(applied.status).isEqualTo(201)
                assertThat(json.readTree(applied.body).get("appliedCount").asInt()).isEqualTo(3)
            }
        }

    private companion object {
        const val LATE_MILLIS = 2500L
        const val POLLS = 60
        const val POLL_MILLIS = 250L
        const val SWEEP_OLD_MINUTES = 60L
    }
}
