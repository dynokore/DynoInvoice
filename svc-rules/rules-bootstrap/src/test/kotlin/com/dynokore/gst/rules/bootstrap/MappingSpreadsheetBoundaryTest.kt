package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.web.ImportCommand
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetCsv
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetImports
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.application.MappingPackPermissions
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.USER_A_TOKEN
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val CONSOLE = "console-bff"
private const val ALT = "-alt"
private const val PACKS = "/internal/v1/rules/mapping-packs"
private const val IMPORTS = "/internal/v1/rules/mapping-imports"
private const val ENTITY = "E-1"
private const val USER_B_TOKEN = "Bearer token-user-b"
private const val CAP = 5_000
private const val BUDGET_MILLIS = 2_000L
private const val BOM = 0xFEFF

/** What the doubles of the spreadsheet tests were asked, and the switches that steer them. */
object SpreadsheetProbe {
    val authorityAsked = CopyOnWriteArrayList<Triple<String, String, String>>()
    val importCalls = AtomicInteger()
    val catalogueCalls = AtomicInteger()

    /** The one permission key the fake authority grants. */
    @Volatile var grantedKey: String = MappingPackPermissions.AUTHOR

    /** Probe only: the authority writes one outbox row, so that the table comparison has something to find. */
    @Volatile var writeOutboxRow = false

    fun reset() {
        authorityAsked.clear()
        importCalls.set(0)
        catalogueCalls.set(0)
        grantedKey = MappingPackPermissions.AUTHOR
        writeOutboxRow = false
    }
}

/** The authority fake that records the key it is asked for, plus a counting use case and a counting catalogue. */
@TestConfiguration(proxyBeanMethods = false)
class SpreadsheetDoubles {
    @Bean
    fun packActionAuthority(emitter: PackEventEmitter): PackActionAuthority =
        PackActionAuthority { actor, entity, permission ->
            SpreadsheetProbe.authorityAsked.add(Triple(actor, entity, permission))
            if (SpreadsheetProbe.writeOutboxRow) {
                emitter.emit(
                    listOf(PackEvent.RulePackActivated("p", "v", LocalDate.of(2026, 1, 1))),
                    EventContext(
                        TENANT_A,
                        EntityRef("entity-1"),
                        null,
                        null,
                        "agg",
                        null,
                        CorrelationId("corr"),
                        CausationId("corr"),
                    ),
                )
            }
            permission == SpreadsheetProbe.grantedKey || SpreadsheetProbe.grantedKey == "*"
        }

    @Bean
    @Primary
    fun countingImports(
        @Qualifier("mappingSpreadsheetImports") real: MappingSpreadsheetImports,
    ): MappingSpreadsheetImports =
        MappingSpreadsheetImports { command: ImportCommand ->
            SpreadsheetProbe.importCalls.incrementAndGet()
            real.import(command)
        }

    @Bean
    @Primary
    fun countingCatalogue(reader: PackContentsReader): CodeCataloguePort =
        CodeCataloguePort { tenant: TenantId?, date: TransactionDate ->
            SpreadsheetProbe.catalogueCalls.incrementAndGet()
            reader.catalogueAt(tenant, date)
        }

    @Bean
    fun spreadsheetBuild(): org.springframework.boot.info.BuildProperties =
        org.springframework.boot.info.BuildProperties(
            java.util.Properties().apply { setProperty("version", "2026.11.1") },
        )
}

private fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * The mapping spreadsheet round trip at the service boundary, on real PostgreSQL (INV-REQ-831, INV-REQ-833;
 * INV-AC-844, INV-AC-2039, INV-AC-2040, INV-AC-2059, INV-AC-3484): import, export, the import record and the entry
 * link, the 5,000-row cap, the scoped permission and the refused caller.
 */
@SpringBootTest(
    classes = [MappingDoubles::class, SpreadsheetDoubles::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE",
        "service-runtime.workload-identity.callers[1].service=$CONSOLE$ALT",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CONSOLE$ALT",
    ],
)
class MappingSpreadsheetBoundaryTest(
    @LocalServerPort port: Int,
) : MappingPackSupport(port) {
    private var counter = 0

    @BeforeEach
    fun reset() = SpreadsheetProbe.reset()

    private fun hash() = sha("file-${++counter}-${UUID.randomUUID()}")

    private fun row(
        code: String,
        iras: String = "SR",
        sourceClass: String = "PRIMARY",
        citation: String? = null,
    ): String {
        val cite = citation?.let { ""","citation":${mapper.writeValueAsString(it)}""" }.orEmpty()
        return """{"internalTaxCode":"$code","irasCode":"$iras","sourceClass":"$sourceClass",""" +
            """"description":"ignored","rate":"0"$cite}"""
    }

    private fun body(
        rows: List<String>,
        hash: String = hash(),
        rowCount: Int? = rows.size,
        entityRef: String? = ENTITY,
        name: String = "mapping.csv",
    ): String {
        val parts =
            listOfNotNull(
                """"fileHash":"$hash"""",
                """"fileName":"$name"""",
                """"rows":[${rows.joinToString(",")}]""",
                rowCount?.let { """"rowCount":$it""" },
                entityRef?.let { """"entityRef":"$it"""" },
            )
        return "{" + parts.joinToString(",") + "}"
    }

    private fun import(
        body: String,
        user: String = USER_A_TOKEN,
    ) = call("POST", "$PACKS/import", body, user)

    private fun records(
        hash: String,
        entity: String = ENTITY,
    ) = json(call("GET", "$IMPORTS?fileHash=$hash&entityRef=$entity")).get("items")

    private fun count(sql: String) = (value(TENANT_A, sql) as Number).toInt()

    private fun evidenceRows(importId: String) =
        rows(
            TENANT_A,
            "select data::text as d from rules.outbox where type like '%EvidenceArtefactRecorded%' " +
                "and data::text like '%$importId%'",
        )

    private fun valid(n: Int) = (1..n).map { row("C%05d".format(Locale.ROOT, it)) }

    // ---- INV-AC-2040: a clean re-import leaves its record and produces a draft ----

    @Test
    fun `INV-AC-2040 a clean re-import produces a draft and leaves its import record linked to every entry`() {
        val hash = hash()
        val answer = ok(import(body(listOf(row("A1"), row("A2", "ZR", "SECONDARY", "Annex E, para 2")), hash)), 201)
        answer.get("status").asString() shouldBe "DRAFT"
        answer.get("entryCount").asInt() shouldBe 2
        val packId = answer.get("packId").asString()
        val record = records(hash).single()
        record.get("fileHash").asString() shouldBe hash
        record.get("fileName").asString() shouldBe "mapping.csv"
        record.get("rowCount").asInt() shouldBe 2
        record.get("dryRunResult").asString() shouldBe "CLEAN"
        record.get("appliedCount").asInt() shouldBe 2
        (record.get("rejectedRows")?.size() ?: 0) shouldBe 0
        record.get("packId").asString() shouldBe packId
        val importId = record.get("importId").asString()
        val entries = ok(call("GET", "$PACKS/$packId/entries?entityRef=$ENTITY")).get("items")
        entries.size() shouldBe 2
        entries.forEach { it.get("importRef").asString() shouldBe importId }
        // an entry authored by hand has no import reference
        ok(mapCode(packId, "HAND"), 201).has("importRef") shouldBe false
        ok(call("GET", "$PACKS/$packId/entries?entityRef=$ENTITY&limit=10")).get("items").count {
            it.has("importRef")
        } shouldBe 2
        // the record is published as EvidenceArtefactRecorded, artefact type IMPORT, hash the file's SHA-256
        val published = mapper.readTree(evidenceRows(importId).single()["d"].toString())
        published.get("artefactType").asString() shouldBe "IMPORT"
        published.get("reference").asString() shouldBe importId
        published.get("hash").asString() shouldBe hash
    }

    @Test
    fun `INV-AC-2040 the actor of the record is the principal of the token, not a request value or a role`() {
        val hash = hash()
        val extra = ""","actorRef":"mallory","actor":"tax.operator"}"""
        val withActor = body(listOf(row("A1")), hash).removeSuffix("}") + extra
        ok(import(withActor, USER_B_TOKEN), 201)
        records(hash).single().get("actorRef").asString() shouldBe "user-b"
        value(TENANT_A, "select actor from rules.mapping_import_record where file_hash = ?", hash) shouldBe "user-b"
        // the author of the draft is the same principal (INV-Q-10647: a user ID, never a role)
        value(
            TENANT_A,
            "select registered_by from rules.pack_registry where pack_id = ?",
            records(hash).single().get("packId").asString(),
        ) shouldBe "user-b"
    }

    // ---- INV-AC-2039 and INV-AC-2059: a rejected row leaves a record and no draft ----

    @Test
    fun `INV-AC-2039 a code absent from the IRAS catalogue raises RUL-006 and creates no draft`() {
        val hash = hash()
        val packsBefore = rows(TENANT_A, "select t::text as r from rules.pack_registry t order by 1")
        val entriesBefore = rows(TENANT_A, "select t::text as r from rules.pack_entry_source t order by 1")
        val answer = import(body(listOf(row("A1"), row("A2", "ZZ"), row("A3"), row("A4", "QQ")), hash))
        answer.statusCode() shouldBe 422
        val problem = json(answer)
        io.kotest.assertions.withClue(answer.body().take(400)) {
            problem.get("code").asString() shouldBe "RUL-006"
            problem.has("errors") shouldBe true
        }
        io.kotest.assertions.withClue(answer.body().take(400)) {
            problem.get("errors").toList().map { it.path("field").asString() } shouldBe listOf("row 2", "row 4")
        }
        rows(TENANT_A, "select t::text as r from rules.pack_registry t order by 1") shouldBe packsBefore
        rows(TENANT_A, "select t::text as r from rules.pack_entry_source t order by 1") shouldBe entriesBefore
        val record = records(hash).single()
        record.get("dryRunResult").asString() shouldBe "REJECTED"
        record.get("rejectedRows").toList().map { it.path("row").asInt() to it.path("code").asString() } shouldBe
            listOf(2 to "RUL-006", 4 to "RUL-006")
    }

    @Test
    fun `INV-AC-2059 a re-import with a rejected row leaves its record with hash, actor and result and no draft`() {
        val hash = hash()
        val before = snapshot()
        import(body(listOf(row("A1"), row("A2", "ZZ")), hash)).statusCode() shouldBe 422
        val after = snapshot()
        // the only tables that change are the record and the outbox (the evidence event)
        after.filterKeys { it != "mapping_import_record" && it != "outbox" } shouldBe
            before.filterKeys { it != "mapping_import_record" && it != "outbox" }
        after.getValue("mapping_import_record").size shouldBe before.getValue("mapping_import_record").size + 1
        val record = records(hash).single()
        record.get("fileHash").asString() shouldBe hash
        record.get("actorRef").asString() shouldBe "user-a"
        record.get("dryRunResult").asString() shouldBe "REJECTED"
        record.get("appliedCount").asInt() shouldBe 0
        record.get("rowCount").asInt() shouldBe 2
        record.has("packId") shouldBe false
        record
            .get("rejectedRows")
            .single()
            .get("row")
            .asInt() shouldBe 2
        record
            .get("rejectedRows")
            .single()
            .get("code")
            .asString() shouldBe "RUL-006"
        evidenceRows(record.get("importId").asString()).size shouldBe 1
    }

    @Test
    fun `a record of another file hash or of another entity is not listed`() {
        val hash = hash()
        ok(import(body(listOf(row("A1")), hash)), 201)
        records(hash).size() shouldBe 1
        records(hash.dropLast(1) + (if (hash.last() == '0') "1" else "0")).size() shouldBe 0
        records(hash, "E-2").size() shouldBe 0
        call("GET", "$IMPORTS?fileHash=$hash").statusCode() shouldBe 400
    }

    @Test
    fun `a repeat of a clean file returns the same draft and stores nothing`() {
        val hash = hash()
        val first = ok(import(body(listOf(row("A1")), hash)), 201).get("packId").asString()
        val before = snapshot()
        ok(import(body(listOf(row("A1")), hash)), 201).get("packId").asString() shouldBe first
        snapshot() shouldBe before
    }

    // ---- INV-AC-3484: the 5,000-row cap ----

    @Test
    fun `INV-AC-3484 a file over 5000 rows is refused whole with 413 and a file of 5000 rows is one draft`() {
        val before = snapshot()
        val over = import(body(valid(CAP + 1)))
        io.kotest.assertions.withClue(over.body().take(300)) { over.statusCode() shouldBe 413 }
        json(over).get("code").asString() shouldBe "SRT-014"
        SpreadsheetProbe.catalogueCalls.get() shouldBe 0
        SpreadsheetProbe.authorityAsked.shouldBeEmpty()
        snapshot() shouldBe before
        // the look-alike: 5,001 rows with a matching rowCount is still 413, never 201
        import(body(valid(CAP + 1), rowCount = CAP + 1)).statusCode() shouldBe 413
        // the unparsed rows are not validated: an over-cap file of unknown codes is 413, not 422
        import(body((1..CAP + 1).map { row("U$it", "ZZ") })).statusCode() shouldBe 413
        snapshot() shouldBe before
        val hash = hash()
        val five = import(body(valid(CAP), hash))
        five.statusCode() shouldBe 201
        json(five).get("entryCount").asInt() shouldBe CAP
        val fivePack = json(five).get("packId").asString()
        count("select count(*) from rules.pack_registry where pack_id = '$fivePack'") shouldBe 1
        records(hash).single().get("appliedCount").asInt() shouldBe CAP
    }

    @Test
    fun `INV-AC-3484 the 5000-row file is answered within the synchronous budget of the operation`() {
        val started = System.nanoTime()
        val answer = import(body(valid(CAP)))
        val millis = (System.nanoTime() - started) / 1_000_000
        answer.statusCode() shouldBe 201
        io.kotest.assertions.withClue("$millis ms against $BUDGET_MILLIS ms") {
            (millis <= BUDGET_MILLIS) shouldBe true
        }
    }

    @Test
    fun `a request without rows, without entityRef or with a different rowCount is 400 SRT-001 and stores nothing`() {
        val before = snapshot()
        val shapes =
            listOf(
                """{"fileHash":"${hash()}","fileName":"f.csv","rowCount":1,"entityRef":"$ENTITY"}""",
                body(listOf(row("A1")), entityRef = null),
                body(valid(CAP), rowCount = CAP - 1), // the look-alike: 5,000 rows, rowCount 4,999
                body(valid(2), rowCount = 3),
                body(valid(1), rowCount = null),
                body(emptyList(), rowCount = 0),
                body(listOf(row("A1"), row("A1"))), // the internal code occurs twice
                body(listOf(row("A1")), hash = "not-a-sha256"),
            )
        shapes.forEach {
            val answer = import(it)
            answer.statusCode() shouldBe 400
            json(answer).get("code").asString() shouldBe "SRT-001"
        }
        val bogus =
            """{"fileHash":"${hash()}","fileName":"f.csv","rowCount":1,"entityRef":"$ENTITY",""" +
                """"rows":[{"internalTaxCode":"A","irasCode":"SR","sourceClass":"BOGUS"}]}"""
        call("POST", "$PACKS/import", bogus)
            .statusCode() shouldBe 400
        SpreadsheetProbe.catalogueCalls.get() shouldBe 0
        snapshot() shouldBe before
    }

    @Test
    fun `the generated request carries the property rows`() {
        Class.forName("com.dynokore.gst.rules.contract.model.ImportMappingSpreadsheetRequest").declaredFields.map {
            it.name
        } shouldBe
            listOf("fileHash", "fileName", "rows", "rowCount", "entityRef")
    }

    // ---- the scoped permission, never a role name (INV-Q-10027) ----

    @Test
    fun `the import is decided by the scoped key for the entity of the call and another key is refused`() {
        ok(import(body(listOf(row("A1")))), 201)
        SpreadsheetProbe.authorityAsked.last() shouldBe Triple("user-a", ENTITY, MappingPackPermissions.AUTHOR)
        SpreadsheetProbe.authorityAsked.last().third shouldBe "mapping-pack.author"
        SpreadsheetProbe.grantedKey = MappingPackPermissions.APPROVE
        val before = snapshot()
        val answer = import(body(listOf(row("A2"))))
        answer.statusCode() shouldBe 403
        answer.body() shouldContain "SRT-004"
        snapshot() shouldBe before
    }

    // ---- refused caller leaves nothing stored (INV-Q-12122) ----

    @Test
    fun `importMappingSpreadsheet refuses a caller the edge does not name and leaves nothing stored`() {
        val request = body(listOf(row("A1")))
        val before = snapshot()
        val wrong = call("POST", "$PACKS/import", request, workload = workloadToken(CONSOLE + ALT))
        wrong.statusCode() shouldBe 403
        wrong.body() shouldContain "SRT-004"
        call("POST", "$PACKS/import", request, workload = null).statusCode() shouldBe 401
        SpreadsheetProbe.importCalls.get() shouldBe 0
        snapshot() shouldBe before
        // the named caller is admitted and reaches the use case: the refusal is specific to the caller
        ok(import(request), 201)
        SpreadsheetProbe.importCalls.get() shouldBe 1
    }

    @Test
    fun `probe - the comparison can fail around a call of the named caller that writes one outbox row`() {
        val before = snapshot()
        SpreadsheetProbe.writeOutboxRow = true
        SpreadsheetProbe.grantedKey = "none" // the call is refused 403 after the fake wrote its row
        import(body(listOf(row("A1")))).statusCode() shouldBe 403
        val after = snapshot()
        (after == before) shouldBe false
        after.getValue("outbox").size shouldBe before.getValue("outbox").size + 1
        after.filterKeys { it != "outbox" } shouldBe before.filterKeys { it != "outbox" }
    }

    // ---- INV-AC-844: the round trip ----

    @Suppress("CyclomaticComplexMethod", "LongMethod") // a small RFC 4180 state machine
    private fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }

                quoted && c == '"' -> {
                    quoted = false
                }

                quoted -> {
                    field.append(c)
                }

                c == '"' -> {
                    quoted = true
                }

                c == ',' -> {
                    fields += field.toString()
                    field.clear()
                }

                c == '\r' -> {}

                c == '\n' -> {
                    fields += field.toString()
                    field.clear()
                    records += fields.toList()
                    fields.clear()
                }

                else -> {
                    field.append(c)
                }
            }
            i++
        }
        return records
    }

    private fun entriesOf(packId: String) =
        ok(call("GET", "$PACKS/$packId/entries?entityRef=$ENTITY&limit=500"))
            .get("items")
            .toList()
            .associateBy { it.get("internalTaxCode").asString() }

    @Test
    @Suppress("LongMethod") // one scenario: export, edit three rows, re-import, compare
    fun `INV-AC-844 an exported pack re-imports as a draft with three changed rows and nothing active`() {
        val source = draft("rt-${UUID.randomUUID().toString().take(6)}")
        val codes = (1..6).map { "T$it" }
        codes.forEach { ok(mapCode(source, it, "SR", if (it == "T6") "SECONDARY" else "PRIMARY"), 201) }
        val version = value(TENANT_A, "select pack_version from rules.pack_registry where pack_id = ?", source)
        // citations, one with a comma and one with a quote (RFC 4180 quoting)
        val cite = "update rules.pack_entry_source set source_citation = ? where pack_version = ? and entry_id = ?"
        value(TENANT_A, "$cite returning id", "Annex E, para 2", version, "T1")
        value(TENANT_A, "$cite returning id", "the \"IRAS\" guide", version, "T2")
        val export = call("GET", "$PACKS/$source/export?entityRef=$ENTITY")
        export.statusCode() shouldBe 200
        export.headers().firstValue("Content-Type").get() shouldStartWith "text/csv"
        val text = export.body()
        text shouldStartWith "internalTaxCode,description,irasCode,rate,sourceClass,citation\r\n"
        (text.first().code != BOM) shouldBe true
        val table = parseCsv(text)
        table.first() shouldBe listOf("internalTaxCode", "description", "irasCode", "rate", "sourceClass", "citation")
        table.size shouldBe codes.size + 1
        table.drop(1).forEach {
            it.size shouldBe 6
            it[1] shouldBe "SUPPLY_STANDARD_RATED" // the Annex E group of the category code
            it[3] shouldBe "9" // its annexERate
        }
        // change three rows' irasCode in the file text, then map the file lines to rows
        val changed =
            text
                .split("\r\n")
                .mapIndexed { index, line -> if (index in 1..3) line.replace(",SR,", ",ZR,") else line }
                .joinToString("\r\n")
        val mapped = parseCsv(changed).drop(1)
        val rows =
            mapped.map {
                row(it[0], it[2], it[4], it[5].ifEmpty { null })
            }
        val newPack = ok(import(body(rows)), 201)
        newPack.get("status").asString() shouldBe "DRAFT"
        val before = entriesOf(source)
        val after = entriesOf(newPack.get("packId").asString())
        after.keys shouldBe before.keys

        fun iras(
            entries: Map<String, tools.jackson.databind.JsonNode>,
            code: String,
        ) = entries.getValue(code).get("irasCode").asString()
        val differing = codes.filter { iras(after, it) != iras(before, it) }
        differing.size shouldBe 3
        after.filterKeys { it !in differing }.forEach { (code, entry) ->
            entry.get("irasCode").asString() shouldBe before.getValue(code).get("irasCode").asString()
            entry.get("sourceClass").asString() shouldBe before.getValue(code).get("sourceClass").asString()
        }
        val citationOf =
            "select source_citation from rules.pack_entry_source where entry_id = ? and pack_version = " +
                "(select pack_version from rules.pack_registry where pack_id = ?)"
        listOf("T1", "T2").forEach {
            value(TENANT_A, citationOf, it, newPack.get("packId").asString()) shouldBe
                value(TENANT_A, citationOf, it, source)
        }
        // the draft is not active; activation still needs sign-off
        val newId = newPack.get("packId").asString()
        count("select count(*) from rules.pack_registry where status = 'ARMED' and pack_id = '$newId'") shouldBe 0
        SpreadsheetProbe.grantedKey = "*" // every key held: the refusal below is the lifecycle
        activate(newPack.get("packId").asString(), "2026-01-01").statusCode() shouldBe 409
    }

    @Test
    fun `the export file follows RFC 4180 and its header is exact`() {
        MappingSpreadsheetCsv.HEADER shouldBe "internalTaxCode,description,irasCode,rate,sourceClass,citation"
        val lookAlike = "internalTaxCode,description,irasCode,rate,sourceClass,sourceReference"
        (MappingSpreadsheetCsv.HEADER == lookAlike) shouldBe false
        MappingSpreadsheetCsv.field("a,b") shouldBe "\"a,b\""
        MappingSpreadsheetCsv.field("say \"x\"") shouldBe "\"say \"\"x\"\"\""
        MappingSpreadsheetCsv.field("plain") shouldBe "plain"
        MappingSpreadsheetCsv.field("line\nbreak") shouldBe "\"line\nbreak\""
        parseCsv("a,\"b,c\"\r\n") shouldBe listOf(listOf("a", "b,c"))
        // an unquoted comma shifts the columns: the look-alike a file reader must not accept as six fields
        parseCsv("A,desc, with comma,SR,9,PRIMARY,\r\n").first().size shouldBe 7
        // a byte order mark in the first line is not the header
        ((BOM.toChar() + MappingSpreadsheetCsv.HEADER) == MappingSpreadsheetCsv.HEADER) shouldBe false
    }

    @Test
    fun `an unknown pack or another entity's pack cannot be exported`() {
        val source = draft("ex-${UUID.randomUUID().toString().take(6)}")
        call("GET", "$PACKS/MAP-NONE/export?entityRef=$ENTITY").statusCode() shouldBe 404
        call("GET", "$PACKS/$source/export?entityRef=E-2").statusCode() shouldBe 403
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}
