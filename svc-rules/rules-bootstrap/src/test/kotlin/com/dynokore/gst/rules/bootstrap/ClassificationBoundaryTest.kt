package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val TENANT_CL: TenantId = TenantId.of("0c000000-0000-0000-0000-00000000000c")
private const val USER_CL_TOKEN = "Bearer token-user-cl"
private const val CL_CALLER = "canonical-svc"
private const val CL_DRAFT_CALLER = "payload-svc"
private const val CL_FROM = "2026-01-01"
private const val CL_PATH_DOCUMENT = "/internal/v1/rules/documents/evaluations"
private const val CL_PATH_DRAFT = "/internal/v1/rules/pre-issuance-drafts/evaluations"
private const val CL_DAY = "2027-03-01"
private const val CL_BEFORE_SCOPE = "2025-12-01"
private const val CL_ENTITY = "cl-entity"
private const val SCOPE_VERSION = "cs1"

private const val CL_INSERT_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by) " +
        "values (?::uuid, ?::uuid, ?, ?, ?, 'TENANT_MAPPING', 'ARMED', ?::date, null, " +
        "'1.0.0', null, 'tenancy-probe') returning id"

private const val CL_INSERT_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to, " +
        "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by) " +
        "values (?::uuid, ?, ?, ?, ?::uuid, ?::date, null, '1.0.0', '{}'::jsonb, '{}'::jsonb, " +
        "?, ?::jsonb, 'anchor', 'tenancy-probe') returning id"

/** One row of the invented classification table of the fixture scope pack. */
private data class Row(
    val upstream: String,
    val kind: String,
    val sign: String,
    val function: String,
    val code: String?,
    val normalised: Boolean,
    val cancellation: Boolean = false,
) {
    val key get() = "$upstream/$kind/$sign"

    fun json() =
        """{"upstreamFunction":"$upstream","upstreamKind":"$kind","signOfTotal":"$sign",""" +
            """"documentFunction":"$function",""" +
            code?.let { """"documentTypeCode":"$it",""" }.orEmpty() +
            """"signNormalised":$normalised,"cancellation":$cancellation,""" +
            """"sourceClass":"PRIMARY","sourceReference":"t"}"""
}

private val NEGATIVE_INVOICE = Row("BILL_FOR_PAYMENT", "INVOICE", "NEGATIVE", "ADJUSTMENT", "381", true)
private val PLAIN_INVOICE = Row("BILL_FOR_PAYMENT", "INVOICE", "POSITIVE", "BILL_FOR_PAYMENT", "380", false)
private val DEBIT_NOTE = Row("ADJUSTMENT", "DEBITNOTE", "POSITIVE", "ADJUSTMENT", "380", false)
private val CANCELLATION = Row("ADJUSTMENT", "CANCEL", "NEGATIVE", "ADJUSTMENT", "381", true, cancellation = true)
private val NOT_A_BILL_ROW = Row("NOT_A_BILL", "STATEMENT", "POSITIVE", "NOT_A_BILL", null, false)
private val ROWS = listOf(NEGATIVE_INVOICE, PLAIN_INVOICE, DEBIT_NOTE, CANCELLATION, NOT_A_BILL_ROW)

/** Invented pack data of this test; no value here is IRAS data. All packs are owned by [TENANT_CL]. */
private object ClPacks {
    private fun code(
        code: String,
        group: String,
    ) = """{"code":"$code","group":"$group","side":"SUPPLY","annexERate":"9","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    const val MAPPING =
        """{"entries":[{"entryId":"e1","internalTaxCode":"STD","irasCode":"SR","sourceClass":"PRIMARY"}]}"""
    val catalogue = """{"complete":true,"codes":[${code("SR", "SUPPLY_STANDARD_RATED")}]}"""

    const val SCENARIO =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"s"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"ZR",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s","rateBasis":"FIXED","rate":"0"}],""" +
            """"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""

    val scope =
        """{"classificationTable":[${ROWS.joinToString(",") { it.json() }}],"lineScopeRules":[""" +
            """{"categoryCode":"SR","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY",""" +
            """"sourceReference":"invented"}],""" +
            """"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    val packs =
        listOf(
            Triple("MAPPING", "cl-mapping", "m1") to ("2025-01-01" to MAPPING),
            Triple("CODE_CATALOGUE", "cl-catalogue", "c1") to ("2025-01-01" to catalogue),
            Triple("SCENARIO", "cl-scenario", "s1") to ("2025-01-01" to SCENARIO),
            Triple("SCOPE", "cl-scope", SCOPE_VERSION) to (CL_FROM to scope),
        )
}

@TestConfiguration(proxyBeanMethods = false)
class ClassificationBoundaryDoubles {
    @Bean
    fun clEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            if (authorization == USER_CL_TOKEN) EndUserPrincipal("user-cl", TENANT_CL, emptySet()) else null
        }

    @Bean
    fun clWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified("subject-of-" + value.removePrefix("wl-"))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }

    @Bean
    fun clock(): Clock = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC)
}

/**
 * The classification result through `evaluateDocument` and `evaluatePreIssuanceDraft` of the started context, on real
 * PostgreSQL (INV-REQ-371, INV-REQ-658, INV-REQ-659). The SCOPE pack, with its classification table, and the mapping,
 * catalogue and scenario packs are persisted rows seeded as the tenant; the scope port is the production one.
 */
@SpringBootTest(
    classes = [ClassificationBoundaryDoubles::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CL_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CL_CALLER",
        "service-runtime.workload-identity.callers[1].service=$CL_DRAFT_CALLER",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CL_DRAFT_CALLER",
    ],
)
class ClassificationBoundaryTest(
    @Autowired private val replica: StructureReplica,
    @org.springframework.boot.test.web.server.LocalServerPort private val localPort: Int,
) {
    private val mapper = JsonMapper()
    private val client = HttpClient.newHttpClient()

    init {
        replica.accept(
            EntityProfileChanged(
                TENANT_CL,
                CL_ENTITY,
                StructureNode(
                    StructureNodeKind.ENTITY,
                    null,
                    false,
                    "E",
                    CL_ENTITY,
                    listOf(ProfileEntry("obligationStartDate", CL_FROM, LocalDate.parse(CL_FROM))),
                    1,
                ),
            ),
        )
    }

    @Suppress("LongParameterList") // the parts of one request body, each overridable by a look-alike case
    private fun request(
        row: Row?,
        date: String = CL_DAY,
        kind: String? = row?.kind,
        function: String? = row?.upstream,
        sign: String? = row?.sign,
        code: String = "STD",
        extra: String = "",
    ) = """{"entityRef":"$CL_ENTITY","transactionDate":"$date","internalTaxCode":"$code"""" +
        function?.let { ""","documentFunction":"$it"""" }.orEmpty() +
        kind?.let { ""","documentKind":"$it"""" }.orEmpty() +
        sign?.let { ""","lineSign":"$it"""" }.orEmpty() + extra + "}"

    private fun post(
        path: String,
        json: String,
    ): JsonNode {
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$localPort$path"))
                    .header("Content-Type", "application/json")
                    .header("X-Workload-Token", "wl-" + if (path == CL_PATH_DRAFT) CL_DRAFT_CALLER else CL_CALLER)
                    .header("Authorization", USER_CL_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        response.statusCode() shouldBe 200 // a refusal is an answer (INV-AC-10201)
        return mapper.readTree(response.body())
    }

    private fun both(json: String): List<JsonNode> = listOf(CL_PATH_DOCUMENT, CL_PATH_DRAFT).map { post(it, json) }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private fun JsonNode.classification(name: String): String? = get("decisionTrace")?.get("classification")?.text(name)

    private fun JsonNode.version(packType: String): String? =
        get("packVersionsApplied")
            ?.firstOrNull { it.get("packType").asString() == packType }
            ?.get("version")
            ?.asString()

    private fun JsonNode.shouldBeResultOf(row: Row) {
        text("documentFunction") shouldBe row.function
        text("documentTypeCode") shouldBe row.code
        get("signNormalised")?.asBoolean() shouldBe row.normalised
        get("cancellation")?.asBoolean() shouldBe (if (row.cancellation) true else null)
        text("code") shouldBe null
        classification("ruleId") shouldBe "FR-AE.61"
        classification("tableKey") shouldBe row.key
        classification("packVersion") shouldBe SCOPE_VERSION
        version("SCOPE") shouldBe SCOPE_VERSION
    }

    private fun JsonNode.shouldBeRefusedWith(code: String) {
        text("code") shouldBe code
        text("severity") shouldBe "BLOCK"
        text("documentTypeCode") shouldBe null
        text("categoryCode") shouldBe null
        text("rate") shouldBe null
    }

    @Test
    fun `INV-AC-14810 INV-AC-1717 a negative-total invoice row gives 381 with signNormalised true`() {
        both(request(NEGATIVE_INVOICE)).forEach {
            it.shouldBeResultOf(NEGATIVE_INVOICE)
            it.text("documentTypeCode") shouldBe "381"
            it.get("signNormalised").asBoolean() shouldBe true
            it.text("categoryCode") shouldBe "SR" // a classified document is coded as before
        }
        // the positive invoice of the same kind is another row: 380, not normalised
        both(request(PLAIN_INVOICE)).forEach { it.shouldBeResultOf(PLAIN_INVOICE) }
    }

    @Test
    fun `INV-AC-14810 INV-AC-402 a debit note row gives 380, with and without the preceding document fields`() {
        val preceding = ""","precedingDocumentNumber":"INV-1","precedingDocumentDate":"2026-09-20""""
        both(request(DEBIT_NOTE)).forEach {
            it.shouldBeResultOf(DEBIT_NOTE)
            it.text("documentTypeCode") shouldBe "380"
        }
        both(request(DEBIT_NOTE, extra = preceding)).forEach {
            it.shouldBeResultOf(DEBIT_NOTE)
            it.has("precedingDocumentNumber") shouldBe false
            it.has("precedingDocumentDate") shouldBe false
        }
        // only a number, no date: the debit note never refuses for it
        both(request(DEBIT_NOTE, extra = ""","precedingDocumentNumber":"INV-1"""")).forEach {
            it.shouldBeResultOf(DEBIT_NOTE)
        }
    }

    @Test
    fun `INV-AC-14810 INV-AC-1651 a cancellation row gives 381 with cancellation true`() {
        both(request(CANCELLATION)).forEach {
            it.shouldBeResultOf(CANCELLATION)
            it.text("documentTypeCode") shouldBe "381"
            it.get("cancellation").asBoolean() shouldBe true
        }
        // the other rows carry no cancellation
        both(request(NEGATIVE_INVOICE)).forEach { it.has("cancellation") shouldBe false }
    }

    @Test
    fun `INV-AC-14810 a NOT_A_BILL row gives no documentTypeCode`() {
        both(request(NOT_A_BILL_ROW)).forEach {
            it.shouldBeResultOf(NOT_A_BILL_ROW)
            it.has("documentTypeCode") shouldBe false
            it.text("documentFunction") shouldBe "NOT_A_BILL"
        }
    }

    @Test
    fun `INV-AC-14810 an asserted NOT_A_BILL with no row stays NOT_A_BILL with no table result`() {
        both(request(NEGATIVE_INVOICE, function = "NOT_A_BILL", sign = "POSITIVE")).forEach {
            it.text("documentFunction") shouldBe "NOT_A_BILL"
            listOf("documentTypeCode", "signNormalised", "cancellation").forEach { name -> it.has(name) shouldBe false }
            it.get("decisionTrace")?.has("classification") ?: false shouldBe false
            it.version("SCOPE") shouldBe null
            it.text("code") shouldBe null
            it.text("categoryCode") shouldBe "SR" // coding and the scope verdict run as for a NOT_A_BILL row
        }
    }

    @Test
    fun `INV-AC-14810 a request with no documentFunction is not classified and adds no scope pack item`() {
        both(request(null)).forEach {
            listOf("documentFunction", "documentTypeCode", "signNormalised", "cancellation").forEach { name ->
                it.has(name) shouldBe false
            }
            it.get("decisionTrace")?.has("classification") ?: false shouldBe false
            it.version("SCOPE") shouldBe null
            it.text("code") shouldBe null
            it.text("categoryCode") shouldBe "SR"
        }
    }

    @Test
    fun `INV-AC-14810 look-alike keys select no row and are answered RUL-012 with no type code`() {
        // another refused line (an unmapped code), whose scope verdict the classification refusals must equal
        val unmapped = post(CL_PATH_DOCUMENT, request(null, code = "NO-SUCH-CODE"))
        listOf(
            request(NEGATIVE_INVOICE, kind = "INVOICE "), // trailing blank
            request(NEGATIVE_INVOICE, kind = "INVOICE2"), // a longer name
            request(NEGATIVE_INVOICE, kind = "invoice"), // the other case
            request(NEGATIVE_INVOICE, function = "ADJUSTMENT"), // the same kind, another upstream function
            request(NEGATIVE_INVOICE, sign = "ZERO"), // the same kind, another line sign
            request(NEGATIVE_INVOICE, kind = null), // a function without a kind
            request(NEGATIVE_INVOICE, sign = null), // a function without a sign
            request(NOT_A_BILL_ROW, kind = null), // NOT_A_BILL without a kind: the key is incomplete, fail closed
            request(NOT_A_BILL_ROW, sign = null), // NOT_A_BILL without a sign
        ).forEach { json ->
            both(json).forEach {
                it.shouldBeRefusedWith("RUL-012")
                it.text("scopeVerdict") shouldBe unmapped.text("scopeVerdict")
                it.version("SCOPE") shouldBe SCOPE_VERSION
                it.has("documentFunction") shouldBe false
            }
        }
        unmapped.text("scopeVerdict") shouldBe "NOT_SUBMITTABLE"
    }

    @Test
    fun `INV-AC-14810 a date with no scope pack in force is answered RUL-002 and nothing is classified`() {
        both(request(NEGATIVE_INVOICE, date = CL_BEFORE_SCOPE)).forEach {
            it.shouldBeRefusedWith("RUL-002")
            it.text("scopeVerdict") shouldBe "NOT_SUBMITTABLE"
            it.version("SCOPE") shouldBe null
            it.has("documentFunction") shouldBe false
        }
    }

    @Test
    fun `INV-AC-14810 the classification table is read at the transaction date, not the preceding document date`() {
        val early = ""","precedingDocumentNumber":"INV-1","precedingDocumentDate":"$CL_BEFORE_SCOPE""""
        // the preceding date has no scope pack, the transaction date has: the table of the transaction date classifies
        // (the line itself is then refused RUL-002 under the preceding date, as before this task)
        both(request(DEBIT_NOTE, extra = early)).forEach {
            it.text("documentFunction") shouldBe DEBIT_NOTE.function
            it.text("documentTypeCode") shouldBe "380"
            it.classification("tableKey") shouldBe DEBIT_NOTE.key
            it.classification("packVersion") shouldBe SCOPE_VERSION
            it.text("code") shouldBe "RUL-002"
        }
    }

    @Test
    fun `INV-AC-14810 a refused classification stores nothing`() {
        val owner = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

        fun snapshot() =
            owner
                .fetchRows(
                    "select table_name from information_schema.tables " +
                        "where table_schema = 'rules' and table_type = 'BASE TABLE' order by 1",
                ).map { it["table_name"] as String }
                .associateWith { table ->
                    owner.inTenant(TENANT_CL) {
                        fetchRows("select t::text as r from rules.\"$table\" t order by 1").map { it["r"] }
                    }
                }
        val before = snapshot()
        both(request(NEGATIVE_INVOICE, kind = "INVOICE ")).forEach { it.shouldBeRefusedWith("RUL-012") }
        snapshot() shouldBe before
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
            ClPacks.packs.forEach { (id, window) ->
                val (type, packId, version) = id
                val (from, contents) = window
                probe.inTenant(TENANT_CL) {
                    fetchValue(
                        CL_INSERT_PACK,
                        UUID.randomUUID().toString(),
                        TENANT_CL.toString(),
                        packId,
                        type,
                        version,
                        from,
                    )
                    fetchValue(
                        CL_INSERT_ARTEFACT,
                        UUID.randomUUID().toString(),
                        packId,
                        type,
                        version,
                        TENANT_CL.toString(),
                        from,
                        byteArrayOf(1),
                        contents,
                    )
                }
            }
        }
    }
}
