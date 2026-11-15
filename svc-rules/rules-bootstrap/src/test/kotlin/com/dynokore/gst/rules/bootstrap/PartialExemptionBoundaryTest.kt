package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.domain.scope.PartialExemption
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancyjooq.TenancyProbe
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val PE_PATH = "/internal/v1/rules/documents/evaluations"
private const val PE_CALLER = "canonical-svc"
private const val PE_FROM = "2026-01-01"
private const val PE_SCOPE_SWITCH = "2028-01-01"
private const val IN_SCOPE = "IN_SCOPE"
private const val OUT_OF_SCOPE = "OUT_OF_SCOPE"
private const val PURCHASE = ""","direction":"PURCHASE""""
private const val UNCLAIMED = ""","inputTaxClaimedNonZero":false"""

private const val PE_INSERT_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
        "values (?::uuid, ?::uuid, ?, ?, ?, 'TENANT_MAPPING', 'ARMED', ?::date, ?::date, " +
        "'1.0.0', null, 'tenancy-probe', ?) returning id"

private const val PE_INSERT_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to, " +
        "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by) " +
        "values (?::uuid, ?, ?, ?, ?::uuid, ?::date, ?::date, '1.0.0', '{}'::jsonb, '{}'::jsonb, " +
        "?, ?::jsonb, 'anchor', 'tenancy-probe') returning id"

private data class PePack(
    val type: String,
    val packId: String,
    val version: String,
    val from: String,
    val to: String?,
    val contents: String,
)

/** Invented pack data of this test; no value here is IRAS data. All packs are owned by [TENANT_PE]. */
private object PePacks {
    private fun map(
        internal: String,
        iras: String,
    ) = """{"entryId":"e-$internal","internalTaxCode":"$internal","irasCode":"$iras","sourceClass":"PRIMARY"}"""

    val mapping =
        "{\"entries\":[" +
            listOf(
                map("RE", "TX-RE"),
                map("ESS", "TX-ESS"),
                map("N33", "TX-N33"),
                map("BLX", "BL"),
                map("MIXED", "TX-MU"),
            ).joinToString(",") + "]}"

    private fun code(
        code: String,
        group: String,
    ) = """{"code":"$code","group":"$group","side":"PURCHASE","annexERate":"9","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    val catalogue =
        """{"complete":true,"codes":[""" +
            listOf(
                code("TX-RE", "PURCHASE_STANDARD_RATED"),
                code("TX-ESS", "PURCHASE_STANDARD_RATED"),
                code("TX-MU", "PURCHASE_STANDARD_RATED"),
                code("TX", "PURCHASE_STANDARD_RATED"),
                code("TX-N33", "PURCHASE_ZERO_RATED_NON_REPORTABLE"),
                code("BL", "PURCHASE_ZERO_RATED_NON_REPORTABLE"),
            ).joinToString(",") + "]}"

    const val SCENARIO =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"s"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"ZR",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s","rateBasis":"FIXED","rate":"0"}],""" +
            """"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""

    private fun rule(
        code: String,
        verdict: String,
    ) = """{"categoryCode":"$code","lineVerdict":"$verdict","sourceClass":"PRIMARY","sourceReference":"invented"}"""

    private fun scope(blVerdict: String) =
        """{"classificationTable":[{"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"INVOICE",""" +
            """"signOfTotal":"POSITIVE","documentFunction":"BILL_FOR_PAYMENT","documentTypeCode":"380",""" +
            """"signNormalised":false,"sourceClass":"PRIMARY","sourceReference":"t"}],"lineScopeRules":[""" +
            listOf(
                rule("TX-RE", "IN_SCOPE"),
                rule("TX-ESS", "IN_SCOPE"),
                rule("TX-N33", "IN_SCOPE"),
                rule("TX-MU", "IN_SCOPE"),
                rule("TX", "IN_SCOPE"),
                rule("BL", blVerdict),
            ).joinToString(",") +
            """],"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    /** (type, packId, version, from, to, contents): SCOPE s1 lists BL as OUT_OF_SCOPE, s2 (2028) as EXCLUDED_TXN. */
    val packs =
        listOf<PePack>(
            PePack("MAPPING", "pe-mapping", "m1", PE_FROM, null, mapping),
            PePack("CODE_CATALOGUE", "pe-catalogue", "c1", PE_FROM, null, catalogue),
            PePack("SCENARIO", "pe-scenario", "s1", PE_FROM, null, SCENARIO),
            PePack("SCOPE", "pe-scope", "sc1", PE_FROM, PE_SCOPE_SWITCH, scope("OUT_OF_SCOPE")),
            PePack("SCOPE", "pe-scope", "sc2", PE_SCOPE_SWITCH, null, scope("EXCLUDED_TXN")),
        )
}

/**
 * The partial-exemption, election and apportionment criteria through `POST evaluateDocument` of the started context
 * (INV-REQ-332, INV-REQ-366 to INV-REQ-370, INV-REQ-382). Seams: the `ScopeVerdictPort` is the production
 * [PackScopeVerdictPort] over the persisted SCOPE pack (the test excludes `BoundaryDoubles`); the MAPPING, catalogue,
 * scenario and SCOPE packs are rows of `rules.pack_registry` and `rules.pack_artefact` seeded as the tenant through
 * `TenancyProbe`; the entity profile is `StructureReplica.accept(EntityProfileChanged)` on the replica bean of the
 * context. The clock is fixed ([PE_CLOCK]).
 */
@SpringBootTest(
    classes = [PartialExemptionBoundaryDoubles::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$PE_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$PE_CALLER",
    ],
)
class PartialExemptionBoundaryTest(
    @Autowired private val port: ScopeVerdictPort,
    @Autowired private val replica: StructureReplica,
    @org.springframework.boot.test.web.server.LocalServerPort private val localPort: Int,
) {
    private val mapper = JsonMapper()
    private val client = HttpClient.newHttpClient()

    private val day = "2027-03-01"

    /** Records the profile of a fresh entity; the entries take effect on [effective] (default: the obligation date). */
    private fun entity(
        vararg entries: Pair<String, String>,
        effective: String = PE_FROM,
        also: List<ProfileEntry> = emptyList(),
    ): String {
        val ref = "pe-entity-${entityCounter.incrementAndGet()}"
        record(
            ref,
            listOf(ProfileEntry("obligationStartDate", PE_FROM, LocalDate.parse(PE_FROM))) + also +
                entries.map { ProfileEntry(it.first, it.second, LocalDate.parse(effective)) },
        )
        return ref
    }

    private fun record(
        ref: String,
        entries: List<ProfileEntry>,
    ) = replica.accept(
        EntityProfileChanged(
            TENANT_PE,
            ref,
            StructureNode(StructureNodeKind.ENTITY, null, false, "E", ref, entries, 1),
        ),
    )

    private fun evaluate(
        entity: String,
        code: String,
        date: String = day,
        extra: String = PURCHASE,
    ): JsonNode {
        val json = """{"entityRef":"$entity","transactionDate":"$date","internalTaxCode":"$code"$extra}"""
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$localPort$PE_PATH"))
                    .header("Content-Type", "application/json")
                    .header("X-Workload-Token", "wl-$PE_CALLER")
                    .header("Authorization", USER_PE_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        response.statusCode() shouldBe 200
        return mapper.readTree(response.body())
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private fun JsonNode.trace(): List<JsonNode> = get("decisionTrace")?.get("scopeRules")?.toList().orEmpty()

    private fun JsonNode.rules(): List<String> = trace().map { it.get("rule").asString() }

    private fun JsonNode.entry(rule: String): JsonNode = trace().single { it.get("rule").asString() == rule }

    private fun JsonNode.input(
        rule: String,
        name: String,
    ): String? = entry(rule).get("inputs").text(name)

    private val exempt = "partiallyExempt" to "true"

    @Test
    fun `the context holds the production port over the persisted SCOPE pack`() {
        port.javaClass shouldBe PackScopeVerdictPort::class.java
    }

    @Test
    fun `INV-AC-398 a partially exempt purchase with input tax not claimed is IN_SCOPE and keeps TX-RE`() {
        val answer = evaluate(entity(exempt), "RE", extra = PURCHASE + UNCLAIMED)
        answer.text("scopeVerdict") shouldBe IN_SCOPE
        answer.text("categoryCode") shouldBe "TX-RE"
        answer.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
    }

    @Test
    fun `INV-AC-1642 a partially exempt purchase mapped to TX-ESS carries TX-ESS`() {
        val answer = evaluate(entity(exempt), "ESS", extra = PURCHASE + UNCLAIMED)
        answer.text("scopeVerdict") shouldBe IN_SCOPE
        answer.text("categoryCode") shouldBe "TX-ESS"
        answer.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
    }

    @Test
    fun `INV-AC-1643 a partially exempt disallowed expense BL is OUT_OF_SCOPE with no election involved`() {
        val answer = evaluate(entity(exempt), "BLX")
        answer.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        answer.rules() shouldBe listOf(PartialExemption.RULE_NON_REPORTABLE)
        answer.input(PartialExemption.RULE_NON_REPORTABLE, "categoryCode") shouldBe "BL"
        // an election naming BL changes nothing: the exclusion is not the election's
        val elected = evaluate(entity(exempt, "exemptPurchaseCodes" to "BL", "exemptPurchaseBasis" to "b"), "BLX")
        elected.rules() shouldBe listOf(PartialExemption.RULE_NON_REPORTABLE)
    }

    @Test
    fun `INV-AC-2782 a purchase mapped to BL is OUT_OF_SCOPE for a non-partially-exempt entity, pack aside`() {
        val answer = evaluate(entity("partiallyExempt" to "false"), "BLX")
        answer.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        answer.rules() shouldBe listOf(PartialExemption.RULE_NON_REPORTABLE)
        answer.input(PartialExemption.RULE_NON_REPORTABLE, "categoryCode") shouldBe "BL"
        // the SCOPE version in force from 2028 lists BL as EXCLUDED_TXN: still OUT_OF_SCOPE
        val later = evaluate(entity("partiallyExempt" to "false"), "BLX", date = "2028-06-01")
        later.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        later.rules() shouldBe listOf(PartialExemption.RULE_NON_REPORTABLE)
        evaluate(entity(exempt), "BLX", date = "2028-06-01").text("scopeVerdict") shouldBe OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-1656 an entity not recorded as partially exempt gets the general rule for TX-RE`() {
        val plain = entity("partiallyExempt" to "false")
        evaluate(plain, "RE", extra = PURCHASE + UNCLAIMED).let {
            it.rules() shouldBe emptyList()
            it.text("scopeVerdict") shouldBe OUT_OF_SCOPE // general rule: an unclaimed standard-rated purchase
            it.text("categoryCode") shouldBe "TX-RE"
        }
        evaluate(plain, "RE", extra = PURCHASE + ""","inputTaxClaimedNonZero":true""").let {
            it.rules() shouldBe emptyList()
            it.text("scopeVerdict") shouldBe IN_SCOPE // the pack verdict of TX-RE
        }
        listOf("True", "true ", "1", "TRUE", "").forEach { value ->
            val answer = evaluate(entity("partiallyExempt" to value), "RE", extra = PURCHASE + UNCLAIMED)
            answer.rules() shouldBe emptyList()
            answer.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        }
        // dated after the transaction date, and a missing node
        val future = entity("partiallyExempt" to "true", effective = "2027-03-02")
        evaluate(future, "RE", extra = PURCHASE + UNCLAIMED).let {
            it.rules() shouldBe emptyList()
            it.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        }
        // a missing node is no partially exempt status: the profile has no partiallyExempt entry at all
        evaluate(entity(), "RE", extra = PURCHASE + UNCLAIMED).let {
            it.rules() shouldBe emptyList()
            it.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        }
        evaluate("pe-no-such-entity", "RE", extra = PURCHASE + UNCLAIMED).text("scopeVerdict") shouldBe "PRE_OBLIGATION"
    }

    @Test
    fun `INV-AC-399 with the recorded election a partially exempt TX-N33 purchase is OUT_OF_SCOPE`() {
        val answer =
            evaluate(
                entity(exempt, "exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "exempt supplies"),
                "N33",
            )
        answer.text("scopeVerdict") shouldBe OUT_OF_SCOPE
        answer.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION, PartialExemption.RULE_EXEMPT_ELECTION)
        answer.input(PartialExemption.RULE_EXEMPT_ELECTION, "codes") shouldBe "TX-N33"
        answer.input(PartialExemption.RULE_EXEMPT_ELECTION, "basis") shouldBe "exempt supplies"
    }

    @Test
    fun `INV-AC-1645 without the election a partially exempt TX-N33 purchase is IN_SCOPE`() {
        evaluate(entity(exempt), "N33").let {
            it.text("scopeVerdict") shouldBe IN_SCOPE
            it.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
        }
        listOf("TX-N33 ", "tx-n33", "TX-N3").forEach { codes ->
            val answer = evaluate(entity(exempt, "exemptPurchaseCodes" to codes, "exemptPurchaseBasis" to "b"), "N33")
            answer.text("scopeVerdict") shouldBe IN_SCOPE
            answer.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
        }
        // the election of another entity
        entity(exempt, "exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "b")
        evaluate(entity(exempt), "N33").text("scopeVerdict") shouldBe IN_SCOPE
        // an election dated after the transaction date
        val late =
            entity(
                exempt,
                effective = PE_FROM,
                also =
                    listOf(
                        ProfileEntry("exemptPurchaseCodes", "TX-N33", LocalDate.parse("2027-03-02")),
                        ProfileEntry("exemptPurchaseBasis", "b", LocalDate.parse("2027-03-02")),
                    ),
            )
        evaluate(late, "N33").let {
            it.text("scopeVerdict") shouldBe IN_SCOPE
            it.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
        }
        // a blank basis
        val blank = entity(exempt, "exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "  ")
        evaluate(blank, "N33").text("scopeVerdict") shouldBe IN_SCOPE
    }

    @Test
    fun `INV-AC-393 claimable-portion mode submits a mixed-use purchase as TX with no amount in the answer`() {
        val both = PURCHASE + ""","apportionmentMode":"CLAIMABLE_PORTION","mixedUse":"CLAIMABLE_PORTION""""
        val answer = evaluate(entity(), "MIXED", extra = both)
        answer.text("scopeVerdict") shouldBe IN_SCOPE
        answer.text("categoryCode") shouldBe "TX"
        answer.rules() shouldBe listOf(PartialExemption.RULE_APPORTIONMENT)
        val schemas = (Yaml().load<Map<String, Any?>>(openapi())["components"] as Map<*, *>)["schemas"] as Map<*, *>
        val declared = ((schemas["EvaluationResult"] as Map<*, *>)["properties"] as Map<*, *>).keys.map { it as String }
        val names = answer.propertyNames().toList()
        declared shouldContainAll names
        names.filter { Regex("(?i)taxable|inputtax|amount|^tax$").containsMatchIn(it) } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1646 entire-invoice mode, an absent mode and an absent indicator keep the mapped code`() {
        val e = entity()
        listOf(
            ""","apportionmentMode":"ENTIRE_INVOICE","mixedUse":"CLAIMABLE_PORTION"""",
            ""","mixedUse":"CLAIMABLE_PORTION"""",
            ""","apportionmentMode":"CLAIMABLE_PORTION"""",
        ).forEach { extra ->
            val answer = evaluate(e, "MIXED", extra = PURCHASE + extra)
            answer.text("categoryCode") shouldBe "TX-MU"
            answer.rules() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-18751 the status and the election apply from their date D and not before`() {
        val e = entity(exempt, "exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "b", effective = day)
        val before = "2027-02-28"
        evaluate(e, "RE", date = before, extra = PURCHASE + UNCLAIMED).let {
            it.rules() shouldBe emptyList()
        }
        evaluate(e, "N33", date = before).let {
            it.rules() shouldBe emptyList()
            it.text("scopeVerdict") shouldBe IN_SCOPE
        }
        evaluate(e, "RE", date = day, extra = PURCHASE + UNCLAIMED).let {
            it.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
            it.text("scopeVerdict") shouldBe IN_SCOPE
        }
        evaluate(e, "N33", date = day).let {
            it.text("scopeVerdict") shouldBe OUT_OF_SCOPE
            it.rules() shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION, PartialExemption.RULE_EXEMPT_ELECTION)
        }
    }

    @Test
    fun `INV-AC-392 a TX-RE purchase keeps its code and verdict across a period end, no period property`() {
        val e = entity(exempt)
        val inside = evaluate(e, "RE", date = "2026-12-31")
        val after = evaluate(e, "RE", date = "2027-01-01")
        inside.text("categoryCode") shouldBe "TX-RE"
        after.text("categoryCode") shouldBe inside.text("categoryCode")
        after.text("scopeVerdict") shouldBe inside.text("scopeVerdict")
        val schemas = (Yaml().load<Map<String, Any?>>(openapi())["components"] as Map<*, *>)["schemas"] as Map<*, *>
        val request = (schemas["EvaluateDocumentRequest"] as Map<*, *>)["properties"] as Map<*, *>
        val props = request.keys.map { it as String }
        props.filter { Regex("(?i)minimis|period").containsMatchIn(it) } shouldBe emptyList()
    }

    private fun openapi(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("contract/openapi.yaml")) { "no contract document" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }

    companion object {
        /** One counter for the JVM: JUnit builds a class instance per test and the replica keeps its entities. */
        private val entityCounter = AtomicInteger()

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
            PePacks.packs.forEach { pack ->
                probe.inTenant(TENANT_PE) {
                    fetchValue(
                        PE_INSERT_PACK,
                        UUID.randomUUID().toString(),
                        TENANT_PE.toString(),
                        pack.packId,
                        pack.type,
                        pack.version,
                        pack.from,
                        pack.to,
                        if (pack.type == "MAPPING") "E-1" else null,
                    )
                    fetchValue(
                        PE_INSERT_ARTEFACT,
                        UUID.randomUUID().toString(),
                        pack.packId,
                        pack.type,
                        pack.version,
                        TENANT_PE.toString(),
                        pack.from,
                        pack.to,
                        byteArrayOf(1),
                        pack.contents,
                    )
                }
            }
        }
    }
}
