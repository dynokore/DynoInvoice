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
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
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
@Suppress("LargeClass") // one boundary class per task keeps the fixtures and the acceptance keys together
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
        // the unparsed rows are never validated: an over-cap file of unknown codes is 413, not 422
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
        // warm-up (not timed): a request of 5,000 valid rows with its own fileHash in the same JVM and database; the
        // budget is for the synchronous answer of a running service, not for JVM or container start-up
        import(body(valid(CAP))).statusCode() shouldBe 201
        val started = System.nanoTime()
        val answer = import(body(valid(CAP)))
        val millis = (System.nanoTime() - started) / 1_000_000
        answer.statusCode() shouldBe 201
        io.kotest.assertions.withClue("$millis ms against $BUDGET_MILLIS ms") {
            (millis <= BUDGET_MILLIS) shouldBe true
        }
    }

    @Test
    fun `INV-AC-3484 a 5000-row import writes every row as the request names it and links each to its record`() {
        val hash = hash()
        val rows = valid(CAP)
        val answer = ok(import(body(rows, hash)), 201)
        val importId = records(hash).single().get("importId").asString()
        val stored =
            rows(
                TENANT_A,
                "select e.internal_tax_code, e.iras_code, e.source_class, e.source_citation, e.import_ref " +
                    "from rules.pack_entry_source e join rules.pack_registry p on p.pack_version = e.pack_version " +
                    "and p.pack_type = e.pack_type and p.tenant_id = e.tenant_id " +
                    "where p.pack_id = '${answer.get("packId").asString()}' order by e.internal_tax_code",
            )
        stored.size shouldBe CAP
        stored.forEachIndexed { i, r ->
            r["internal_tax_code"] shouldBe "C%05d".format(Locale.ROOT, i + 1)
            r["iras_code"] shouldBe "SR"
            r["source_class"] shouldBe "PRIMARY"
            r["source_citation"] shouldBe null
            r["import_ref"] shouldBe importId
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

/**
 * The two foreign keys of rules.pack_entry_source are statement-level triggers (INV-T-4973, INV-AC-3484): the same
 * refusals and the same stored rows as the constraints, with no privilege added to the runtime login.
 */
@SpringBootTest(classes = [RulesApplication::class])
class MappingImportReferenceIT {
    private val runtime = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val owner = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_migrate", "rules_migrate_pw")
    private val tenant = TenantId(UUID.randomUUID())
    private val other = TenantId(UUID.randomUUID())

    private class RolledBack : RuntimeException()

    private fun counted(statement: String) = "with s as ($statement returning 1) select count(*) from s"

    private fun store(statement: String) = runtime.inTenant(tenant) { fetchValue(counted(statement)) }

    private fun sqlStateOf(failure: Throwable): String =
        generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState

    private fun seedPack(
        owned: TenantId,
        version: String,
    ) = runtime.inTenant(owned) {
        fetchValue(
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "effective_from, min_engine_version, registered_by, entity_ref) values (gen_random_uuid(), " +
                "'$owned'::uuid, 'MAP-$version', 'MAPPING', '$version', 'TENANT_MAPPING', 'DRAFT', " +
                "'2026-01-01'::date, '1.0.0', 'tenancy-probe', 'E-1') returning id",
        )
    }

    private fun seedRecord(
        owned: TenantId,
        importId: String,
    ) = runtime.inTenant(owned) {
        fetchValue(
            "insert into rules.mapping_import_record (tenant_id, import_id, entity_ref, actor, file_hash, file_name, " +
                "row_count, dry_run_result, applied_count, pack_id) values ('$owned'::uuid, '$importId', 'E-1', " +
                "'user-a', 'h-$importId', 'f.csv', 1, 'CLEAN', 1, 'MAP-x') returning import_id",
        )
    }

    private fun insertEntries(
        owned: TenantId,
        version: String,
        n: Int,
        importRef: String? = null,
        prefix: String = "N",
    ): String =
        "insert into rules.pack_entry_source (id, tenant_id, pack_type, pack_version, entry_id, source_class, " +
            "internal_tax_code, iras_code, import_ref) select gen_random_uuid(), '$owned'::uuid, 'MAPPING', " +
            "'$version', '$prefix' || g, 'PRIMARY', '$prefix' || g, 'SR', " +
            (importRef?.let { "'$it'" } ?: "null") + " from generate_series(1, $n) g"

    private fun content(
        probe: TenancyProbe,
        owned: TenantId,
    ): String =
        probe.inTenant(owned) {
            fetchValue(
                "select (select coalesce(string_agg(t::text, '|' order by t.id), '') from rules.pack_entry_source t) " +
                    "|| '#' || (select coalesce(string_agg(t::text, '|' order by t.id), '') " +
                    "from rules.pack_registry t) " +
                    "|| '#' || (select coalesce(string_agg(t::text, '|' order by t.import_id), '') " +
                    "from rules.mapping_import_record t)",
            ) as String
        }

    /** The statement fails with [state], and every row of the three tables reads the same before and after. */
    private fun refused(
        probe: TenancyProbe,
        owned: TenantId,
        state: String,
        statement: String,
        preamble: String? = null,
    ) {
        val before = content(owner, owned)
        val failure =
            shouldThrow<Exception> {
                probe.inTenant(owned) {
                    preamble?.let { fetchValue(it) }
                    fetchValue(counted(statement))
                }
            }
        sqlStateOf(failure) shouldBe state
        content(owner, owned) shouldBe before
    }

    /** Runs [statement] as the owner under a policy the test adds and rolls back; returns the failure state. */
    private fun asOwner(
        owned: TenantId,
        policy: String,
        statement: String,
    ): String? {
        var state: String? = null
        try {
            owner.inTenant(owned) {
                fetchValue(policy)
                try {
                    fetchValue(counted(statement))
                } catch (e: Exception) {
                    state = sqlStateOf(e)
                    throw e
                }
                throw RolledBack()
            }
        } catch (_: Exception) {
        }
        return state
    }

    @Test
    fun `INV-AC-3484 an entry whose import_ref names no import record is refused 23503 and nothing changes`() {
        seedPack(tenant, "a1")
        refused(runtime, tenant, "23503", insertEntries(tenant, "a1", 3, importRef = "missing"))
    }

    @Test
    fun `INV-AC-3484 an entry whose pack names no pack is refused 23503 and nothing changes`() {
        seedPack(tenant, "b1")
        refused(runtime, tenant, "23503", insertEntries(tenant, "no-such-pack", 3))
    }

    @Test
    fun `INV-AC-3484 an update of an entry to a missing reference is refused 23503 and nothing changes`() {
        seedPack(tenant, "c1")
        seedRecord(tenant, "imp-c1")
        store(insertEntries(tenant, "c1", 2, "imp-c1"))
        refused(
            runtime,
            tenant,
            "23503",
            "update rules.pack_entry_source set import_ref = 'missing' where pack_version = 'c1'",
        )
        refused(
            runtime,
            tenant,
            "23503",
            "update rules.pack_entry_source set pack_version = 'no-such-pack' where pack_version = 'c1'",
        )
        // the look-alike that must pass: an update that keeps both references
        runtime.inTenant(tenant) {
            val keep = "update rules.pack_entry_source set accepted = true where pack_version = 'c1'"
            fetchValue(counted(keep)) shouldBe 2L
        }
    }

    @Test
    fun `INV-AC-3484 a delete and a key update of a referenced import record or pack are refused 23503 as the owner`() {
        seedPack(tenant, "d1")
        seedPack(tenant, "d2")
        seedRecord(tenant, "imp-d1")
        seedRecord(tenant, "imp-free")
        store(insertEntries(tenant, "d1", 2, "imp-d1"))
        val mine = "tenant_id = (select rules.bound_tenant())"
        val importDelete = "create policy probe_delete on rules.mapping_import_record for delete using ($mine)"
        val importUpdate =
            "create policy probe_update on rules.mapping_import_record for update using ($mine) with check ($mine)"
        val packDelete = "create policy probe_delete on rules.pack_registry for delete using ($mine)"
        val record = "rules.mapping_import_record"
        asOwner(tenant, importDelete, "delete from $record where import_id = 'imp-d1'") shouldBe "23503"
        asOwner(tenant, importUpdate, "update $record set import_id = 'x' where import_id = 'imp-d1'") shouldBe "23503"
        asOwner(tenant, packDelete, "delete from rules.pack_registry where pack_version = 'd1'") shouldBe "23503"
        // the same key update as the runtime login: the BEFORE trigger pack_registry_frozen_update (V1) refuses a
        // changed pack_version with 42501 before any referential check, on main with the foreign key as well
        val keyUpdate = "update rules.pack_registry set pack_version = 'd1x' where pack_version = 'd1'"
        refused(runtime, tenant, "42501", keyUpdate)
        // look-alikes that must pass: the same statements against rows no entry references
        var passed: Any? = null
        try {
            owner.inTenant(tenant) {
                fetchValue(importDelete)
                passed = fetchValue(counted("delete from $record where import_id = 'imp-free'"))
                throw RolledBack()
            }
        } catch (_: RolledBack) {
        }
        passed shouldBe 1L
        passed = null
        try {
            owner.inTenant(tenant) {
                fetchValue(packDelete)
                passed = fetchValue(counted("delete from rules.pack_registry where pack_version = 'd2'"))
                throw RolledBack()
            }
        } catch (_: RolledBack) {
        }
        passed shouldBe 1L
    }

    @Test
    fun `INV-AC-3484 the runtime login keeps its grants and is still refused 42501 on the parent tables`() {
        seedPack(tenant, "g1")
        seedRecord(tenant, "imp-g1")
        val grants =
            "select string_agg(c.relname || ':' || a.rolname || ':' || x.privilege_type, ',' " +
                "order by c.relname, a.rolname, x.privilege_type) from pg_class c, aclexplode(c.relacl) x " +
                "join pg_roles a on a.oid = x.grantee where c.oid in ('rules.mapping_import_record'::regclass, " +
                "'rules.pack_registry'::regclass) and a.rolname <> 'rules_migrate'"
        val expected =
            "mapping_import_record:rules_runtime:INSERT,mapping_import_record:rules_runtime:SELECT," +
                "pack_registry:rules_runtime:INSERT,pack_registry:rules_runtime:SELECT," +
                "pack_registry:rules_runtime:UPDATE," +
                "pack_registry:rules_sys:INSERT,pack_registry:rules_sys:SELECT,pack_registry:rules_sys:UPDATE"
        runtime.inTenant(tenant) { fetchValue(grants) } shouldBe expected
        refused(runtime, tenant, "42501", "delete from rules.mapping_import_record where import_id = 'imp-g1'")
        refused(runtime, tenant, "42501", "update rules.mapping_import_record set file_name = 'x'")
        refused(runtime, tenant, "42501", "delete from rules.pack_registry where pack_version = 'g1'")
        runtime.inTenant(tenant) { fetchValue(grants) } shouldBe expected
    }

    @Test
    fun `INV-AC-3484 a valid import needs no new privilege and an entry without import_ref is stored`() {
        seedPack(tenant, "e1")
        seedRecord(tenant, "imp-e1")
        runtime.inTenant(tenant) {
            fetchValue(counted(insertEntries(tenant, "e1", 4, "imp-e1"))) shouldBe 4L
            fetchValue(counted(insertEntries(tenant, "e1", 2, null, "P"))) shouldBe 2L
            fetchValue("select count(*) from rules.pack_entry_source where import_ref is null") shouldBe 2L
        }
    }

    @Test
    fun `INV-AC-3484 an entry for another bound tenant is refused by row-level security 42501`() {
        seedPack(tenant, "t1")
        seedPack(other, "t1")
        refused(runtime, tenant, "42501", insertEntries(other, "t1", 1))
    }

    @Test
    fun `INV-AC-3484 a second entry with the same entry_id or the same internal_tax_code is refused 23505`() {
        seedPack(tenant, "u1")
        store(insertEntries(tenant, "u1", 1))
        refused(runtime, tenant, "23505", insertEntries(tenant, "u1", 1))
        refused(
            runtime,
            tenant,
            "23505",
            insertEntries(tenant, "u1", 1, prefix = "Q").replace("'PRIMARY', 'Q' || g", "'PRIMARY', 'N' || g"),
        )
    }

    @Test
    fun `INV-AC-3484 an entry inserted into an ARMED pack is refused 42501`() {
        seedPack(tenant, "r1")
        listOf("SUBMITTED", "APPROVED", "ARMED").forEach {
            val move = "update rules.pack_registry set status = '$it' where pack_version = 'r1' returning id"
            runtime.inTenant(tenant) { fetchValue(move) }
        }
        refused(runtime, tenant, "42501", insertEntries(tenant, "r1", 1))
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
