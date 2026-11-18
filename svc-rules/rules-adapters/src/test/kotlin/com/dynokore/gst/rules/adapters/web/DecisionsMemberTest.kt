package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.domain.classification.DocumentClassifier
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.gst.rules.domain.scope.TraceEntry
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-20803: the `decisions` member of the `decisionTrace` of the `evaluateDocument` answer, for a refused line only
 * (owner ruling 2026-11-18, option A). The real controller, use case and pack reader run over in-memory packs with
 * invented data; the scope port is the production one except where a case needs a rule that fired on a refused line.
 */
class DecisionsMemberTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val start = LocalDate.of(2026, 1, 1)
    private val flow = "eyJhbGciOiJub25lIn0.eyJmYyI6InQifQ.c2ln"

    private val mapping =
        """{"entries":[{"entryId":"e1","internalTaxCode":"SR","irasCode":"SR","sourceClass":"PRIMARY"},""" +
            """{"entryId":"e2","internalTaxCode":"A3S","irasCode":"NA","sourceClass":"PRIMARY","schemeTag":"A3PL"}]}"""
    private val catalogue =
        """{"complete":true,"codes":[""" +
            """{"code":"SR","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
            """"sourceClass":"PRIMARY","sourceReference":"i"},""" +
            """{"code":"NA","group":"SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE","side":"SUPPLY","annexERate":"0",""" +
            """"sourceClass":"PRIMARY","sourceReference":"i"}]}"""
    private val scenario =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},"schemeTagSides":[{""" +
            """"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY","sourceReference":"s"}],""" +
            """"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"NA","rateBasis":"FIXED","rate":"0",""" +
            """"counterpartyApprovedUnder":["MES"],"ibt022Statement":"A3PL statement",""" +
            """"sourceClass":"INFERRED","sourceReference":"i"}],"approvableSchemes":[{"scheme":"MES",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s"}]}"""
    private val scopePack =
        """{"classificationTable":[{"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"INVOICE",""" +
            """"signOfTotal":"POSITIVE","documentFunction":"BILL_FOR_PAYMENT","documentTypeCode":"380",""" +
            """"signNormalised":false,"sourceClass":"PRIMARY","sourceReference":"t"}],"lineScopeRules":[""" +
            """{"categoryCode":"SR","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"t"},""" +
            """{"categoryCode":"NA","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"t"}],""" +
            """"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    private fun version(
        type: PackType,
        label: String,
    ) = PackVersion(PackManifest(PackId("$label-id"), type, PackVersionLabel(label), start, null, "1.0.0"))

    private val held =
        mapOf(
            PackTypes.MAPPING to (version(PackTypes.MAPPING, "m1") to mapping),
            PackTypes.CODE_CATALOGUE to (version(PackTypes.CODE_CATALOGUE, "c1") to catalogue),
            PackTypes.SCENARIO to (version(PackTypes.SCENARIO, "s1") to scenario),
            PackTypes.SCOPE to (version(PackTypes.SCOPE, "sc1") to scopePack),
        )

    /** The rules a custom scope port reports, in order, as fired on the line. */
    private fun ruleTrace(vararg entries: TraceEntry): (PackScopeVerdictPort) -> ScopeVerdictPort =
        { real ->
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts) = real.verdictOf(facts)

                override fun outcomeOf(facts: ScopeFacts): ScopeOutcome =
                    real.outcomeOf(facts).copy(trace = entries.toList())
            }
        }

    @Suppress("LongMethod") // one wiring of the real controller over in-memory packs and replicas
    private fun controller(port: (PackScopeVerdictPort) -> ScopeVerdictPort = { it }): EvaluationController {
        val lookup = PackLookup { type, _, date -> held[type]?.first?.takeIf { !date.isBefore(start) } }
        val artefacts = { v: PackVersion ->
            held.values.firstOrNull { it.first == v }?.second?.let {
                PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), it.toByteArray())
            }
        }
        val reader = PackContentsReader(lookup, artefacts)
        val structure = StructureReplica(clock)
        structure.accept(
            EntityProfileChanged(
                tenant,
                "E1",
                StructureNode(
                    StructureNodeKind.ENTITY,
                    null,
                    false,
                    "E",
                    "E1",
                    listOf(ProfileEntry("obligationStartDate", "2026-03-01", start)),
                    1,
                ),
            ),
        )
        val evaluate =
            EvaluateDocument(
                PackResolver(lookup),
                Evaluator(emptyList()),
                emptyList(),
                DocumentCoder(reader, reader, reader),
                port(PackScopeVerdictPort(lookup, artefacts, reader)),
            )
        val tenants =
            object : TenantContext {
                override fun current() = tenant

                override fun currentActor() = "test"

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        return EvaluationController(
            evaluate,
            reader,
            tenants,
            tx,
            ProfileScopeFacts(structure, clock),
            VoluntaryOverSubmission(PolicyReplica(clock, emptySet())),
            ClassifyDocument(reader),
        )
    }

    private fun answer(
        c: EvaluationController,
        code: String,
        extra: String = "",
    ): EvaluationResult =
        checkNotNull(
            c
                .evaluateDocument(
                    flow,
                    mapper.readValue(
                        """{"entityRef":"E1","transactionDate":"2026-04-01","internalTaxCode":"$code"$extra}""",
                        EvaluateDocumentRequest::class.java,
                    ),
                ).body,
        )

    private val unmapped = "NOPE"
    private val scheme = ""","election":"A3PL","counterpartySchemeData":{"approvedSchemes":["MES"]}"""
    private val noKind = ""","documentFunction":"BILL_FOR_PAYMENT""""

    private fun EvaluationResult.versions() =
        packVersionsApplied.orEmpty().map {
            mapOf(
                "packIdentifier" to it.packType,
                "version" to it.version,
            )
        }

    private fun entry(
        code: String?,
        ruleId: String,
        versions: List<Map<String, Any>>,
        inputs: List<Map<String, String>>,
    ) = listOf(mapOf("code" to code, "ruleId" to ruleId, "packVersions" to versions, "inputs" to inputs))

    @Test
    fun `INV-AC-20803 refused line has one decisions entry with its refusal code and the scope rule id`() {
        val rules =
            arrayOf(
                TraceEntry("R-FIRST", linkedMapOf("a" to "1", "b" to "2")),
                TraceEntry(
                    "R-SECOND",
                    mapOf("c" to "3"),
                ),
            )
        val r = answer(controller(ruleTrace(*rules)), unmapped)
        val code = checkNotNull(r.code)
        Regex("^[A-Z]{3}-[0-9]{3}$").matches(code) shouldBe true
        val trace = checkNotNull(r.decisionTrace)
        trace["decisions"] shouldBe
            entry(
                code,
                "R-FIRST",
                r.versions(),
                listOf(mapOf("name" to "a", "value" to "1"), mapOf("name" to "b", "value" to "2")),
            )
        (r.versions().isNotEmpty()) shouldBe true
        trace.keys shouldBe setOf("scopeRules", "decisions")
        trace.containsKey("decision") shouldBe false
    }

    @Test
    fun `INV-AC-20803 classification refusal has the classifier rule id`() {
        val r = answer(controller(), "SR", noKind)
        r.code shouldBe "RUL-012"
        val trace = checkNotNull(r.decisionTrace)
        trace["decisions"] shouldBe entry("RUL-012", DocumentClassifier.RULE_ID, r.versions(), emptyList())
        trace.keys shouldBe setOf("decisions")
        (DocumentClassifier.RULE_ID.length in 1..80) shouldBe true
    }

    @Test
    fun `INV-AC-20803 refused line without a rule id has no decisions member`() {
        val r = answer(controller(), unmapped)
        (r.code != null) shouldBe true
        r.decisionTrace.orEmpty().containsKey("decisions") shouldBe false
        r.decisionTrace shouldBe null
    }

    @Test
    fun `INV-AC-20803 coded line scheme tag line and scope rule decision have no decisions member`() {
        val plain = answer(controller(), "SR")
        plain.code shouldBe null
        plain.decisionTrace shouldBe null
        val tagged = answer(controller(), "A3S", scheme)
        tagged.code shouldBe null
        checkNotNull(tagged.decisionTrace)["handler"] shouldBe "A3PL"
        tagged.decisionTrace
            .orEmpty()
            .keys
            .none { it == "decisions" || it == "decision" } shouldBe true
        val ruled = answer(controller(ruleTrace(TraceEntry("R-ONLY", mapOf("k" to "v")))), "SR")
        ruled.code shouldBe null
        checkNotNull(ruled.decisionTrace).keys shouldBe setOf("scopeRules")
    }

    @Test
    fun `INV-AC-20803 existing trace keys are unchanged`() {
        val tagged = answer(controller(), "A3S", scheme)
        val withRule = answer(controller(ruleTrace(TraceEntry("R-1", mapOf("k" to "v")))), unmapped)
        val before = checkNotNull(withRule.decisionTrace)
        before["scopeRules"] shouldBe listOf(mapOf("rule" to "R-1", "inputs" to mapOf("k" to "v")))
        DecisionEntries.traceWith(tagged.decisionTrace, emptyList()) shouldBe tagged.decisionTrace
        val kept = checkNotNull(tagged.decisionTrace)
        val extended = checkNotNull(DecisionEntries.traceWith(kept, listOf(mapOf("code" to "RUL-001"))))
        extended.filterKeys { it != "decisions" } shouldBe kept
        runCatching { DecisionEntries.traceWith(extended, listOf(mapOf("code" to "RUL-001"))) }.isFailure shouldBe true
    }

    @Test
    fun `INV-AC-20803 a request with no trace answers none`() {
        answer(controller(), "SR").decisionTrace shouldBe null
        DecisionEntries.traceWith(null, emptyList()) shouldBe null
    }

    @Test
    fun `INV-AC-20803 an entry is never created without a valid code or rule id`() {
        val versions =
            listOf(
                com.dynokore.gst.rules.contract.model
                    .PackVersionApplied("MAPPING", "m1"),
            )
        val rule = listOf(TraceEntry("R-1", emptyMap()))
        DecisionEntries.of("srt-001", rule, null, versions) shouldBe emptyList()
        DecisionEntries.of("SRT-0011", rule, null, versions) shouldBe emptyList()
        DecisionEntries.of(null, rule, null, versions) shouldBe emptyList()
        DecisionEntries.of("SRT-001", emptyList(), null, versions) shouldBe emptyList()
        DecisionEntries.of("SRT-001", listOf(TraceEntry("", emptyMap())), null, versions) shouldBe emptyList()
        DecisionEntries.of("SRT-001", listOf(TraceEntry("x".repeat(81), emptyMap())), null, versions) shouldBe
            emptyList()
        DecisionEntries.of("SRT-001", rule, null, emptyList()) shouldBe emptyList()

        fun v(
            type: String,
            version: String,
        ) = listOf(
            com.dynokore.gst.rules.contract.model
                .PackVersionApplied(type, version, packIdentifier = "MAP-0042"),
        )
        DecisionEntries.of("SRT-001", rule, null, v("T".repeat(101), "v")) shouldBe emptyList()
        DecisionEntries.of("SRT-001", rule, null, v("MAPPING", "v".repeat(41))) shouldBe emptyList()
        DecisionEntries.of("SRT-001", rule, null, v("MAPPING", "")) shouldBe emptyList()
        DecisionEntries.of("SRT-001", rule, null, v("T".repeat(100), "v".repeat(40))).size shouldBe 1
        // the packIdentifier is the pack type value, never the item's own pack id (task design)
        DecisionEntries.of("SRT-001", rule, null, v("MAPPING", "7")).single()["packVersions"] shouldBe
            listOf(mapOf("packIdentifier" to "MAPPING", "version" to "7"))
        DecisionEntries.of("SRT-001", listOf(TraceEntry("R-1", mapOf("n" to "v".repeat(501)))), null, versions) shouldBe
            emptyList()
        DecisionEntries
            .of(
                "SRT-001",
                listOf(TraceEntry("R-1", mapOf("n" to "v".repeat(500)))),
                null,
                versions,
            ).size shouldBe
            1
    }
}
