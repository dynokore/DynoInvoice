package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.application.CodeDocumentCommand
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.DocumentCodingResult
import com.dynokore.gst.rules.application.DocumentLine
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.coding.MappingCodeChecks
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.gst.rules.served.ServedDoubles
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.USER_A_TOKEN
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val PLATFORM_TENANT = "ffffffff-ffff-ffff-ffff-ffffffffffff"
private const val PATH_DOCUMENT = "/internal/v1/rules/documents/evaluations"
private const val PATH_DRAFT = "/internal/v1/rules/pre-issuance-drafts/evaluations"
private const val CANONICAL = "canonical-svc"
private const val PAYLOAD = "payload-svc"
private const val ALT = "-alt"

private const val INSERT_MAPPING_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by) " +
        "values (?::uuid, ?::uuid, 'mapping-pack', 'MAPPING', ?, 'TENANT_MAPPING', 'ARMED', ?::date, ?::date, " +
        "'1.0.0', ?, 'tenancy-probe') returning id"

private const val INSERT_MAPPING_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to, " +
        "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by) " +
        "values (?::uuid, 'mapping-pack', 'MAPPING', ?, ?::uuid, ?::date, ?::date, '1.0.0', " +
        "'{}'::jsonb, '{}'::jsonb, " +
        "?, ?::jsonb, 'anchor', 'tenancy-probe') returning id"

private const val REGISTER_PLATFORM_REGISTRY =
    "select rules.register_platform_pack(?::uuid, ?, ?, ?, 'ARMED', ?::date, null, '1.0.0', null)"

private const val REGISTER_PLATFORM_PACK =
    "select rules.register_platform_pack(?::uuid, ?, ?, ?, ?::date, null, '1.0.0', ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)"

/** Invented pack data of this test; no value here is IRAS data. */
private object BoundaryPacks {
    private fun mapEntry(
        id: String,
        internal: String,
        iras: String,
        tag: String? = null,
    ) = """{"entryId":"$id","internalTaxCode":"$internal","irasCode":"$iras","sourceClass":"PRIMARY"""" +
        tag?.let { ""","schemeTag":"$it"""" }.orEmpty() + "}"

    private fun mapping(change: String) =
        "{\"entries\":[" +
            listOf(
                mapEntry("e1", "STD", "SR"),
                mapEntry("e2", "CHG", change),
                mapEntry("e3", "GONE", "ZZ"),
                mapEntry("e4", "SB", "SR", "SELF_BILLING"),
                mapEntry("e5", "NEWCODE", "NEWX"),
                mapEntry("e6", "NOCAT", "SRCA-C"),
                mapEntry("e7", "CAS", "SRCA-S"),
                mapEntry("e8", "CAP", "TXCA"),
            ).joinToString(",") + "]}"

    private fun code(
        code: String,
        rate: String,
        group: String = "SUPPLY_STANDARD_RATED",
        side: String = "SUPPLY",
    ) = """{"code":"$code","group":"$group","side":"$side","annexERate":"$rate","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    private fun catalogue(
        complete: Boolean,
        sr: String,
        vararg more: String,
    ) = """{"complete":$complete,"codes":[${(
        listOf(
            code("SR", sr),
            code("SRCA-C", sr),
            code("SRCA-S", "0"),
            code("TXCA", sr, "PURCHASE_STANDARD_RATED", "PURCHASE"),
            zero(),
        ) + more
    ).joinToString(",")}]}"""

    private fun zero() = code("ZR", "0", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE")

    private fun scenario(
        cumIssuer: String,
        cumRecipient: String,
    ): String {
        fun party(
            direction: String,
            type: String,
        ) = """{"direction":"$direction","submissionType":"$type","sourceClass":"INFERRED","sourceReference":"s"}"""
        return """{"selfBillingAssignment":{"customerCumIssuer":${party("PURCHASE", cumIssuer)},""" +
            """"supplierCumRecipient":${party("SALE", cumRecipient)}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"s"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"ZR",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s","rateBasis":"FIXED","rate":"0"}],""" +
            """"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""
    }

    /** The mapping versions of the tenant: m1 until 2026-10-01 and m2 from it, which changes the code of CHG. */
    val mappings =
        listOf(
            Triple("m1", "2026-07-01" to "2026-10-01", mapping("SR")),
            Triple("m2", "2026-10-01" to null, mapping("ZR")),
        )

    /** The catalogue versions: c1 (incomplete, SR at 8) and c2 (complete, SR at 9, adds NEWX) from 2026-09-01. */
    val catalogues =
        listOf(
            Triple("c1", "2026-07-01", catalogue(false, "8")),
            Triple("c2", "2026-09-01", catalogue(true, "9", code("NEWX", "9"))),
        )

    /** The scenario versions: s2 from 2026-08-01 swaps the self-billing assignment. */
    val scenarios =
        listOf(
            Triple("s1", "2026-07-01", scenario("TYPE_3", "TYPE_2")),
            Triple("s2", "2026-08-01", scenario("TYPE_2", "TYPE_3")),
        )
}

/** The scope rules double: counts the calls the use case makes and can write one outbox row (probe only). */
class BoundaryScope(
    private val emitter: PackEventEmitter,
) : ScopeVerdictPort {
    val calls = AtomicInteger()

    @Volatile var writeOutboxRow = false

    override fun verdictOf(facts: ScopeFacts): ScopeVerdict {
        calls.incrementAndGet()
        if (writeOutboxRow) {
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
        return ScopeVerdict.SUBMITTABLE
    }
}

@TestConfiguration(proxyBeanMethods = false)
class BoundaryDoubles {
    @Bean
    @org.springframework.context.annotation.Primary // the production port is a bean too; this double is the one asked
    fun boundaryScope(emitter: PackEventEmitter): BoundaryScope = BoundaryScope(emitter)
}

/**
 * `evaluateDocument` and `evaluatePreIssuanceDraft` at the service boundary, on real PostgreSQL: the packs are the
 * persisted holder copies resolved by the date of the request (INV-Q-7438), the caller is admitted by the `x-sync-edge`
 * of the operation, and a refused caller leaves nothing stored and never reaches the use case (INV-Q-12122).
 */
@SpringBootTest(
    classes = [ServedDoubles::class, RulesApplication::class, BoundaryDoubles::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CANONICAL",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CANONICAL",
        "service-runtime.workload-identity.callers[1].service=$CANONICAL$ALT",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CANONICAL$ALT",
        "service-runtime.workload-identity.callers[2].service=$PAYLOAD",
        "service-runtime.workload-identity.callers[2].subject=subject-of-$PAYLOAD",
        "service-runtime.workload-identity.callers[3].service=$PAYLOAD$ALT",
        "service-runtime.workload-identity.callers[3].subject=subject-of-$PAYLOAD$ALT",
    ],
)
class EvaluateDocumentBoundaryTest(
    @Autowired private val scope: BoundaryScope,
    @Autowired private val reader: PackContentsReader,
    @Autowired private val tenants: TenantContext,
    @Autowired private val tx: TransactionRunner,
    @org.springframework.boot.test.web.server.LocalServerPort private val port: Int,
) {
    private val mapper = JsonMapper()
    private val client = HttpClient.newHttpClient()

    private fun body(
        code: String,
        date: String,
        extra: String = "",
    ) = """{"entityRef":"entity-1","transactionDate":"$date","internalTaxCode":"$code"$extra}"""

    private fun post(
        path: String,
        json: String,
        workload: String? = workloadToken(CANONICAL),
        authorization: String? = USER_A_TOKEN,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
        workload?.let { request.header("X-Workload-Token", it) }
        authorization?.let { request.header("Authorization", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun evaluate(
        code: String,
        date: String,
        extra: String = "",
    ): JsonNode {
        val answer = post(PATH_DOCUMENT, body(code, date, extra))
        answer.statusCode() shouldBe 200
        return mapper.readTree(answer.body())
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private fun JsonNode.version(packType: String): String? =
        get("packVersionsApplied")
            ?.firstOrNull { it.get("packType").asString() == packType }
            ?.get("version")
            ?.asString()

    @Test
    fun `INV-AC-395 a back-dated invoice is coded under the mapping version in force on its transaction date`() {
        val before = evaluate("CHG", "2026-09-15")
        before.text("categoryCode") shouldBe "SR"
        before.version("MAPPING") shouldBe "m1"
        val after = evaluate("CHG", "2026-10-01")
        after.text("categoryCode") shouldBe "ZR"
        after.version("MAPPING") shouldBe "m2"
        // processed now (the clock is after both dates), the back-dated invoice is still coded under m1
        evaluate("CHG", "2026-09-15") shouldBe before
    }

    @Test
    fun `INV-AC-1639 a date with no effective mapping pack is blocked with RUL-002 and no category code`() {
        val answer = evaluate("STD", "2026-06-20")
        answer.text("code") shouldBe "RUL-002"
        answer.text("severity") shouldBe "BLOCK"
        answer.text("categoryCode") shouldBe null
        answer.text("rate") shouldBe null
    }

    @Test
    fun `INV-AC-1640 a credit note with its preceding document date is coded under that date's version`() {
        val preceding = ""","precedingDocumentNumber":"INV-1","precedingDocumentDate":"2026-09-20""""
        val creditNote = evaluate("CHG", "2026-10-10", preceding)
        creditNote.text("categoryCode") shouldBe "SR"
        creditNote.version("MAPPING") shouldBe "m1"
        // without the preceding-document date the credit note resolves at its own date
        val own = evaluate("CHG", "2026-10-10")
        own.text("categoryCode") shouldBe "ZR"
        own.version("MAPPING") shouldBe "m2"
    }

    @Test
    fun `INV-AC-1641 an unmapped code is RUL-001 and a code absent from the catalogue RUL-006, whatever the flag`() {
        // c1 is flagged incomplete, c2 complete: the answer is the same
        listOf("2026-08-15", "2026-09-15").forEach { date ->
            val unmapped = evaluate("X-NEW", date)
            unmapped.text("code") shouldBe "RUL-001"
            unmapped.text("severity") shouldBe "BLOCK"
            unmapped.text("categoryCode") shouldBe null
            val absent = evaluate("GONE", date)
            absent.text("code") shouldBe "RUL-006"
            absent.text("categoryCode") shouldBe null
        }
    }

    @Test
    fun `INV-AC-1438 a scenario pack release changes the self-billing assignment on its date with no engine change`() {
        fun assignment(
            date: String,
            direction: String,
        ) = evaluate("SB", date, ""","direction":"$direction"""").get("selfBillingAssignment").text("submissionType")
        assignment("2026-07-20", "PURCHASE") shouldBe "TYPE_3"
        assignment("2026-07-20", "SALE") shouldBe "TYPE_2"
        assignment("2026-08-01", "PURCHASE") shouldBe "TYPE_2"
        assignment("2026-08-01", "SALE") shouldBe "TYPE_3"
    }

    @Test
    fun `INV-AC-2430 a mapping entry outside the catalogue is RUL-009 at sign-off and passes once corrected`() {
        val catalogue =
            tenants.bind(TENANT_A, "boundary-test") {
                tx.inTransaction { reader.catalogueAt(TENANT_A, TransactionDate(LocalDate.parse("2026-09-15"))) }
            }!!
        val draft = listOf(MappingEntry("STD", "SR"), MappingEntry("BAD", "SRX"))
        MappingCodeChecks.atSignOff(draft, catalogue).map { it.internalTaxCode to it.code } shouldBe
            listOf("BAD" to ErrorCode.RUL_009)
        MappingCodeChecks.atSignOff(listOf(MappingEntry("STD", "SR"), MappingEntry("BAD", "ZR")), catalogue) shouldBe
            emptyList()
        // the entry that is mapped to a code the catalogue lacks is refused at evaluation, too
        evaluate("GONE", "2026-09-15").text("code") shouldBe "RUL-006"
    }

    private fun rateOf(date: String) = java.math.BigDecimal(evaluate("STD", date).get("rate").asString())

    @Test
    fun `the rate is the one of the catalogue version resolved at the date, and RUL-002 before every version`() {
        rateOf("2026-08-15").compareTo(java.math.BigDecimal("8")) shouldBe 0
        rateOf("2026-09-15").compareTo(java.math.BigDecimal("9")) shouldBe 0
        evaluate("STD", "2026-08-15").version("CODE_CATALOGUE") shouldBe "c1"
        evaluate("STD", "2026-09-15").version("CODE_CATALOGUE") shouldBe "c2"
        // a code added by the later catalogue version is accepted from its date only
        evaluate("NEWCODE", "2026-08-15").text("code") shouldBe "RUL-006"
        evaluate("NEWCODE", "2026-09-15").text("categoryCode") shouldBe "NEWX"
        evaluate("STD", "2026-01-01").text("code") shouldBe "RUL-002"
    }

    @Test
    fun `the customer-accounting code stays valid in the catalogue and is coded as given`() {
        evaluate("NOCAT", "2026-09-15").text("categoryCode") shouldBe "SRCA-C"
    }

    // ---- grouping and the two sides, at DocumentCoder over the persisted packs (INV-Q-14032; no HTTP) ----

    private fun coded(
        date: String,
        vararg lines: Triple<String, String, String>,
    ): DocumentCodingResult =
        tenants.bind(TENANT_A, "boundary-test") {
            tx.inTransaction {
                DocumentCoder(reader, reader, reader).code(
                    CodeDocumentCommand(
                        TENANT_A,
                        TransactionDate(LocalDate.parse(date)),
                        lines.mapIndexed { i, (code, amount, tax) ->
                            DocumentLine("l$i", amount.toBigDecimal(), tax.toBigDecimal(), LineInput(code))
                        },
                    ),
                )
            }
        }

    /** The subtotals as (code, rate, taxable, tax) with numbers as plain strings, so the comparison is by equality. */
    private fun DocumentCodingResult.subtotals(): List<List<String>> =
        shouldBeInstanceOf<DocumentCodingResult.Coded>().document.subtotals.map {
            listOf(
                it.categoryCode,
                it.rate.toPlainString(),
                it.taxable.toPlainString(),
                it.tax.toPlainString(),
            )
        }

    private fun line(
        code: String,
        amount: String,
        tax: String,
    ) = Triple(code, amount, tax)

    @Test
    fun `INV-REQ-363 three lines SR, SR and ZR give exactly two subtotals, SR then ZR, with the sums of the lines`() {
        // 2026-10-10: mapping m2 (CHG is ZR), catalogue c2 (SR at 9), scenario s2
        val sr1 = line("STD", "100.00", "9.00")
        val sr2 = line("STD", "50.50", "4.55")
        coded("2026-10-10", sr1, sr2, line("CHG", "30.00", "0.00")).subtotals() shouldBe
            listOf(listOf("SR", "9", "150.50", "13.55"), listOf("ZR", "0", "30.00", "0.00"))
        // look-alike: SR, SR, SR is one group
        coded("2026-10-10", sr1, sr2, line("STD", "30.00", "2.70")).subtotals() shouldBe
            listOf(listOf("SR", "9", "180.50", "16.25"))
        // look-alike: SR, ZR, ZR is two groups with other sums
        coded("2026-10-10", sr1, line("CHG", "50.50", "0.00"), line("CHG", "30.00", "0.00")).subtotals() shouldBe
            listOf(listOf("SR", "9", "100.00", "9.00"), listOf("ZR", "0", "80.50", "0.00"))
    }

    @Test
    fun `INV-REQ-363 INV-REQ-365 the two sides of one transaction are coded alone and never forced to agree`() {
        val supplierLines = arrayOf(line("CAS", "100.00", "0.00"), line("CAS", "20.00", "0.00"))
        val customerLines = arrayOf(line("CAP", "100.00", "9.00"), line("CAP", "20.00", "1.80"))
        val supplierAlone = coded("2026-10-10", *supplierLines)
        val customerAlone = coded("2026-10-10", *customerLines)
        supplierAlone.subtotals() shouldBe listOf(listOf("SRCA-S", "0", "120.00", "0.00"))
        customerAlone.subtotals() shouldBe listOf(listOf("TXCA", "9", "120.00", "10.80"))
        // one transaction: both sides coded in turn, in either order, give the results they gave alone
        val customerSecond = coded("2026-10-10", *customerLines)
        val supplierSecond = coded("2026-10-10", *supplierLines)
        customerSecond shouldBe customerAlone
        supplierSecond shouldBe supplierAlone
    }

    // ---- refused caller leaves nothing stored (INV-Q-12122) ----

    /** Every table of the schema as the tenant and the platform see it under row-level security. */
    private fun snapshot(): Map<String, List<String>> {
        val owner = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
        val platform = TenantId(UUID.fromString(PLATFORM_TENANT))
        val tables =
            owner
                .fetchRows(
                    "select table_name from information_schema.tables " +
                        "where table_schema = 'rules' and table_type = 'BASE TABLE' order by 1",
                ).map { it["table_name"] as String }
        return tables.associateWith { table ->
            listOf(TENANT_A, platform).flatMap { tenant ->
                owner.inTenant(tenant) {
                    fetchRows("select t::text as r from rules.\"$table\" t order by 1").map { "$tenant ${it["r"]}" }
                }
            }
        }
    }

    @Test
    fun `the snapshot reads every table of the schema and sees the seeded packs`() {
        val tables = snapshot()
        (tables.getValue("pack_registry").size >= 6) shouldBe true
        (tables.getValue("pack_artefact").size >= 6) shouldBe true
        tables.keys.containsAll(listOf("pack_registry", "pack_entry_source", "pack_artefact", "outbox")) shouldBe true
    }

    private fun refusedLeavesNothing(
        path: String,
        caller: String,
    ) {
        val before = snapshot()
        val calls = scope.calls.get()
        val wrong = post(path, body("STD", "2026-09-15"), workloadToken(caller + ALT))
        wrong.statusCode() shouldBe 403
        wrong.body() shouldContain "SRT-004"
        val none = post(path, body("STD", "2026-09-15"), workload = null)
        none.statusCode() shouldBe 401
        scope.calls.get() shouldBe calls
        snapshot() shouldBe before
        // the token of the named caller is admitted and reaches the use case: the refusal is specific to the caller
        val named = post(path, body("STD", "2026-09-15"), workloadToken(caller))
        named.statusCode() shouldBe 200
        scope.calls.get() shouldBe calls + 1
        snapshot() shouldBe before
    }

    @Test
    fun `evaluateDocument refuses a caller the edge does not name and leaves nothing stored`() {
        refusedLeavesNothing(PATH_DOCUMENT, CANONICAL)
    }

    @Test
    fun `evaluatePreIssuanceDraft refuses a caller the edge does not name and leaves nothing stored`() {
        refusedLeavesNothing(PATH_DRAFT, PAYLOAD)
        // canonical-svc is not the caller of the draft edge, and payload-svc not the caller of the document edge
        post(PATH_DRAFT, body("STD", "2026-09-15"), workloadToken(CANONICAL)).statusCode() shouldBe 403
        post(PATH_DOCUMENT, body("STD", "2026-09-15"), workloadToken(PAYLOAD)).statusCode() shouldBe 403
    }

    @Test
    fun `probe the comparison can fail around a call of the named caller that writes one outbox row`() {
        val before = snapshot()
        scope.writeOutboxRow = true
        try {
            post(PATH_DOCUMENT, body("STD", "2026-09-15")).statusCode() shouldBe 200
        } finally {
            scope.writeOutboxRow = false
        }
        val after = snapshot()
        (after == before) shouldBe false
        after.getValue("outbox").size shouldBe before.getValue("outbox").size + 1
        after.filterKeys { it != "outbox" && it != "outbox_sequence" } shouldBe
            before.filterKeys { it != "outbox" && it != "outbox_sequence" }
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            seed()
        }

        private fun seed() {
            val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
            BoundaryPacks.mappings.forEach { (version, window, contents) ->
                val (from, to) = window
                probe.inTenant(TENANT_A) {
                    fetchValue(
                        INSERT_MAPPING_PACK,
                        UUID.randomUUID().toString(),
                        TENANT_A.toString(),
                        version,
                        from,
                        to,
                        null,
                    )
                    fetchValue(
                        INSERT_MAPPING_ARTEFACT,
                        UUID.randomUUID().toString(),
                        version,
                        TENANT_A.toString(),
                        from,
                        to,
                        byteArrayOf(1),
                        contents,
                    )
                }
            }
            registerPlatform(probe, "code-catalogue-pack", "CODE_CATALOGUE", BoundaryPacks.catalogues)
            registerPlatform(probe, "scenario-pack", "SCENARIO", BoundaryPacks.scenarios)
        }

        private fun registerPlatform(
            probe: TenancyProbe,
            packId: String,
            packType: String,
            versions: List<Triple<String, String, String>>,
        ) = versions.forEach { (version, from, contents) ->
            probe.inTenant(TenantId(UUID.fromString(PLATFORM_TENANT))) {
                fetchValue(REGISTER_PLATFORM_REGISTRY, UUID.randomUUID().toString(), packId, packType, version, from)
                fetchValue(
                    REGISTER_PLATFORM_PACK,
                    UUID.randomUUID().toString(),
                    packId,
                    packType,
                    version,
                    from,
                    "{}",
                    "{}",
                    byteArrayOf(1),
                    contents,
                    "anchor",
                )
            }
        }
    }
}
