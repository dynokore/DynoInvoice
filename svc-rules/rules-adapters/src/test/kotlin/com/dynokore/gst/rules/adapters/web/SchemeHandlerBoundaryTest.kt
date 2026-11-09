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
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.PolicySource
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

private const val TOPIC = "gst.platform.policy-changed.v1"

/**
 * The scheme handlers at the service boundary (`evaluateDocument` over the real controller, use case, pack reader and
 * the production [PackScopeVerdictPort]): the packs are held in memory with invented data and the VP-21 election and
 * the entity profile are fed through the replicas' own events. Each acceptance-criterion key sits in the name of the
 * test that proves it.
 */
@Suppress("LargeClass") // one boundary class per task keeps the fixtures and the acceptance keys together
class SchemeHandlerBoundaryTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val start = LocalDate.of(2026, 1, 1)

    private val a3pl = "Payment of GST is not required under the Approved 3PL Company Scheme"
    private val amcs = "Supplies zero-rated under the Approved Marine Customer Scheme"
    private val arcs = "Goods supplied under ARCS"

    private fun cat(
        code: String,
        group: String,
        side: String,
        rate: String = "9",
    ) = """{"code":"$code","group":"$group","side":"$side","annexERate":"$rate","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    private fun entry(
        internal: String,
        iras: String,
        tag: String? = null,
    ) = """{"entryId":"e-$internal","internalTaxCode":"$internal","irasCode":"$iras","sourceClass":"PRIMARY"""" +
        tag?.let { ""","schemeTag":"$it"""" }.orEmpty() + "}"

    private fun tagSide(
        tag: String,
        side: String,
        pending: Boolean = false,
        parent: String? = null,
    ) = """{"tag":"$tag","side":"$side","handling":"${if (pending) "PENDING_QUESTION" else "HANDLED"}",""" +
        parent?.let { """"parentScheme":"$it",""" }.orEmpty() +
        (if (pending) """"pendingReference":"INV-NOTE-4 Q-4",""" else "") +
        """"sourceClass":"PRIMARY","sourceReference":"s"}"""

    @Suppress("LongParameterList") // the members of one invented treatment row
    private fun treatment(
        tag: String,
        side: String,
        code: String,
        under: String,
        statement: String?,
        agreement: String? = null,
    ) = """{"tag":"$tag","side":"$side","categoryCode":"$code","rateBasis":"FIXED","rate":"0",""" +
        """"counterpartyApprovedUnder":[$under],""" +
        agreement?.let { """"writtenAgreement":"$it",""" }.orEmpty() +
        statement?.let { """"ibt022Statement":"$it",""" }.orEmpty() +
        """"sourceClass":"INFERRED","sourceReference":"invented"}"""

    private fun rule(
        code: String,
        verdict: String,
    ) = """{"categoryCode":"$code","lineVerdict":"$verdict","sourceClass":"PRIMARY","sourceReference":"t"}"""

    private val mapping =
        listOf(
            entry("A3S", "NA", "A3PL"),
            entry("A3P", "TXNA", "A3PL"),
            entry("AMS", "ZR", "AMCS"),
            entry("AMP", "ZP", "AMCS"),
            entry("AMSELF", "ZP", "AMCS_SELF_ACCOUNTING"),
            entry("ARS", "NA", "ARCS"),
            entry("ARSR", "SR", "ARCS"),
            entry("ACR", "SR", "ACMT_RECIPIENT_ACCOUNTING"),
            entry("ACOC", "TX", "ACMT_OVERSEAS_CLIENT_PURCHASE"),
            entry("ACDA", "SR", "ACMT"),
            entry("ACSVC", "OS", "ACMT"),
            entry("ACIMP", "IM"),
            entry("CAP", "TX", "CASH_ACCOUNTING"),
            entry("MESS", "SR"),
            entry("SWSS", "ZR", "SWS"),
            entry("IGDSIMP", "IGDS"),
            entry("MEIMP", "ME"),
            entry("IMIMP", "IM"),
            entry("LOOKIGD", "IGD"),
            entry("LOOKME", "ME2"),
        ).joinToString(",", """{"entries":[""", "]}")

    private val catalogue =
        listOf(
            cat("SR", "SUPPLY_STANDARD_RATED", "SUPPLY"),
            cat("ZR", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY", "0"),
            cat("OS", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY", "0"),
            cat("NA", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY", "0"),
            cat("TXNA", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("ZP", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("TX", "PURCHASE_STANDARD_RATED", "PURCHASE"),
            cat("IM", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("ME", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("IGDS", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("IGD", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
            cat("ME2", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE", "0"),
        ).joinToString(",", """{"complete":true,"codes":[""", "]}")

    private fun scenario(
        pendingCash: Boolean = true,
        releasedRecipient: Boolean = false,
    ): String {
        val sides =
            listOf(
                tagSide("A3PL", "SUPPLY"),
                tagSide("A3PL", "PURCHASE"),
                tagSide("AMCS", "SUPPLY"),
                tagSide("AMCS", "PURCHASE"),
                tagSide("ARCS", "SUPPLY"),
                tagSide("ACMT", "SUPPLY"),
                tagSide("SWS", "SUPPLY"),
                tagSide("CASH_ACCOUNTING", "PURCHASE", pending = pendingCash),
                tagSide("AMCS_SELF_ACCOUNTING", "SUPPLY", pending = true),
                tagSide("ACMT_OVERSEAS_CLIENT_PURCHASE", "PURCHASE", pending = true),
                tagSide(
                    "ACMT_RECIPIENT_ACCOUNTING",
                    "SUPPLY",
                    pending = !releasedRecipient,
                    parent = "ACMT".takeIf { releasedRecipient },
                ),
            )
        val treatments =
            listOf(
                treatment("A3PL", "SUPPLY", "NA", """"MES","AISS","ACMT","A3PL"""", a3pl),
                treatment("A3PL", "PURCHASE", "TXNA", """"A3PL"""", a3pl),
                treatment("AMCS", "SUPPLY", "ZR", """"AMCS"""", amcs),
                treatment("AMCS", "PURCHASE", "ZP", """"AMCS"""", amcs),
                treatment("ARCS", "SUPPLY", "NA", """"ARCS"""", arcs, "NOT_AGREED"),
                """{"tag":"ARCS","side":"SUPPLY","categoryCode":"SR","rateBasis":"PREVAILING",""" +
                    """"counterpartyApprovedUnder":["ARCS"],"writtenAgreement":"AGREED",""" +
                    """"sourceClass":"INFERRED","sourceReference":"invented"}""",
            )
        return """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},"schemeTagSides":[""" +
            sides.joinToString(",") + """],"treatments":[""" + treatments.joinToString(",") +
            """],"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""
    }

    private val scopePack =
        """{"classificationTable":[{"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"INVOICE",""" +
            """"signOfTotal":"POSITIVE","documentFunction":"BILL_FOR_PAYMENT","documentTypeCode":"380",""" +
            """"signNormalised":false,"sourceClass":"PRIMARY","sourceReference":"t"}],"lineScopeRules":[""" +
            listOf(
                rule("SR", "IN_SCOPE"),
                rule("ZR", "IN_SCOPE"),
                rule("NA", "IN_SCOPE"),
                rule("TXNA", "IN_SCOPE"),
                rule("ZP", "IN_SCOPE"),
                rule("TX", "IN_SCOPE"),
                rule("OS", "OUT_OF_SCOPE"),
                rule("IM", "EXCLUDED_TXN"),
                rule("ME", "EXCLUDED_TXN"),
                rule("IGDS", "EXCLUDED_TXN"),
                rule("IGD", "EXCLUDED_TXN"),
                rule("ME2", "EXCLUDED_TXN"),
            ).joinToString(",") +
            """],"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    private fun version(
        type: PackType,
        label: String,
    ) = PackVersion(PackManifest(PackId("$label-id"), type, PackVersionLabel(label), start, null, "1.0.0"))

    private class Fixture(
        val controller: EvaluationController,
        val structure: StructureReplica,
        val policy: PolicyReplica,
    )

    @Suppress("LongMethod") // one wiring of the real controller over in-memory packs and replicas
    private fun fixture(scenarioJson: String = scenario()): Fixture {
        val held =
            mapOf(
                PackTypes.MAPPING to (version(PackTypes.MAPPING, "m1") to mapping),
                PackTypes.CODE_CATALOGUE to (version(PackTypes.CODE_CATALOGUE, "c1") to catalogue),
                PackTypes.SCENARIO to (version(PackTypes.SCENARIO, "s1") to scenarioJson),
                PackTypes.SCOPE to (version(PackTypes.SCOPE, "sc1") to scopePack),
            )
        val lookup = PackLookup { type, _, date -> held[type]?.first?.takeIf { !date.isBefore(start) } }
        val artefacts = { v: PackVersion ->
            held.values.firstOrNull { it.first == v }?.second?.let {
                PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), it.toByteArray())
            }
        }
        val reader = PackContentsReader(lookup, artefacts)
        val structure = StructureReplica(clock)
        val policy = PolicyReplica(clock, setOf(TOPIC))
        val evaluate =
            EvaluateDocument(
                PackResolver(lookup),
                Evaluator(emptyList()),
                emptyList(),
                DocumentCoder(reader, reader, reader),
                PackScopeVerdictPort(lookup, artefacts, reader),
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
        val controller =
            EvaluationController(
                evaluate,
                reader,
                tenants,
                tx,
                ProfileScopeFacts(structure, clock),
                VoluntaryOverSubmission(policy),
                ClassifyDocument(reader),
            )
        return Fixture(controller, structure, policy).also { f ->
            f.structure.accept(
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
        }
    }

    private fun vp21(
        f: Fixture,
        value: String,
        entity: String = "E1",
        key: String = "VP-21",
    ) = f.policy.accept(
        PolicyChanged(PolicyScope.entity(tenant, entity), key, value, start, PolicySource.PROFILE, null, TOPIC, 1),
    )

    @Suppress("LongParameterList") // the request members of one invented evaluation
    private fun eval(
        f: Fixture,
        code: String,
        election: String?,
        schemes: List<String>? = null,
        purchase: Boolean = false,
        counterpartyAgreed: Boolean? = null,
        lineAgreed: Boolean? = null,
        electedSchemes: List<String>? = null,
    ): EvaluationResult {
        val json =
            """{"entityRef":"E1","transactionDate":"2026-04-01","internalTaxCode":"$code"""" +
                election?.let { ""","election":"$it"""" }.orEmpty() +
                electedSchemes?.let { ""","electedSchemes":${quoted(it)}""" }.orEmpty() +
                schemes
                    ?.let { s ->
                        ""","counterpartySchemeData":{"approvedSchemes":${quoted(s)}""" +
                            counterpartyAgreed?.let { ""","arcsGstChargeAgreed":$it""" }.orEmpty() + "}"
                    }.orEmpty() +
                lineAgreed?.let { ""","lineArcsGstChargeAgreed":$it""" }.orEmpty() +
                (if (purchase) ""","direction":"PURCHASE"""" else "") + "}"
        return checkNotNull(
            f.controller.evaluateDocument(mapper.readValue(json, EvaluateDocumentRequest::class.java)).body,
        )
    }

    private fun quoted(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    private fun EvaluationResult.treated() = Triple(categoryCode, rate?.toPlainString(), ibt022Statement)

    private fun EvaluationResult.blockedWith(code: String) {
        this.code shouldBe code
        categoryCode shouldBe null
        ibt022Statement shouldBe null
        scopeVerdict shouldBe EvaluationResult.ScopeVerdict.NOT_SUBMITTABLE
    }

    private val inScope = EvaluationResult.ScopeVerdict.IN_SCOPE

    @Test
    fun `INV-AC-2451 an A3PL supply to a customer with MES status is NA at rate 0 with the A3PL statement`() {
        val r = eval(fixture(), "A3S", "A3PL", listOf("MES"))
        r.treated() shouldBe Triple("NA", "0", a3pl)
        r.scopeVerdict shouldBe inScope
        r.decisionTrace?.get("handler") shouldBe "A3PL"
        r.decisionTrace?.get("entrySourceClass") shouldBe "INFERRED"
    }

    @Test
    fun `INV-AC-2452 an A3PL supply to a customer with no status or an ended status is RUL-011`() {
        eval(fixture(), "A3S", "A3PL", null).blockedWith("RUL-011")
        eval(fixture(), "A3S", "A3PL", emptyList()).blockedWith("RUL-011")
    }

    @Test
    fun `INV-AC-2453 an MES entity not electing A3PL codes the purchase TXNA at rate 0 without RUL-004`() {
        eval(fixture(), "A3P", "MES", listOf("A3PL"), purchase = true).treated() shouldBe Triple("TXNA", "0", a3pl)
    }

    @Test
    fun `INV-AC-2536 an A3PL-tagged purchase from a supplier with no A3PL status is RUL-011`() {
        eval(fixture(), "A3P", null, null, purchase = true).blockedWith("RUL-011")
    }

    @Test
    fun `INV-AC-2454 an AMCS supply is ZR and the customer's purchase ZP, each with the AMCS statement`() {
        eval(fixture(), "AMS", "AMCS", listOf("AMCS")).treated() shouldBe Triple("ZR", "0", amcs)
        eval(fixture(), "AMP", "AMCS", listOf("AMCS"), purchase = true).treated() shouldBe Triple("ZP", "0", amcs)
    }

    @Test
    fun `INV-AC-2537 an AMCS supply to a customer whose AMCS status ended is RUL-011 with no statement`() {
        eval(fixture(), "AMS", "AMCS", emptyList()).blockedWith("RUL-011")
    }

    @Test
    fun `INV-AC-2455 an AMCS self-accounting line is RUL-004 and neither coded nor dropped`() {
        eval(fixture(), "AMSELF", "AMCS").blockedWith("RUL-004")
    }

    @Test
    fun `INV-AC-2516 an AMCS customer self-accounting transaction blocks with RUL-004`() {
        val r = eval(fixture(), "AMSELF", "AMCS")
        r.blockedWith("RUL-004")
        r.severity shouldBe EvaluationResult.Severity.BLOCK
        r.decisionTrace?.get("outcome") shouldBe "FLOW_BLOCKED"
    }

    @Test
    fun `INV-AC-2456 an ARCS supply with no written agreement recorded is NA at rate 0 with the ARCS statement`() {
        eval(fixture(), "ARS", "ARCS", listOf("ARCS")).treated() shouldBe Triple("NA", "0", arcs)
    }

    @Test
    fun `INV-AC-2457 the counterparty flag, the line override and both absent decide the ARCS treatment`() {
        val arcsSchemes = listOf("ARCS")
        // counterparty flag set: the NA line contradicts it, the SR line is coded
        eval(fixture(), "ARS", "ARCS", arcsSchemes, counterpartyAgreed = true).blockedWith("RUL-011")
        eval(fixture(), "ARSR", "ARCS", arcsSchemes, counterpartyAgreed = true).categoryCode shouldBe "SR"
        // the line overrides the counterparty flag to not agreed: the waiver applies
        eval(fixture(), "ARS", "ARCS", arcsSchemes, counterpartyAgreed = true, lineAgreed = false).treated() shouldBe
            Triple("NA", "0", arcs)
        // the line overrides the other way
        eval(
            fixture(),
            "ARSR",
            "ARCS",
            arcsSchemes,
            counterpartyAgreed = false,
            lineAgreed = true,
        ).categoryCode shouldBe
            "SR"
        // both absent: no written agreement recorded, the waiver is the default
        eval(fixture(), "ARS", "ARCS", arcsSchemes).treated() shouldBe Triple("NA", "0", arcs)
        // the handler decides the code from the pack row, not from the entry code
        eval(fixture(), "ARSR", "ARCS", arcsSchemes).treated() shouldBe Triple("NA", "0", arcs)
    }

    @Test
    fun `INV-AC-2457 the decision trace names both ARCS inputs and the one used`() {
        val line = eval(fixture(), "ARS", "ARCS", listOf("ARCS"), counterpartyAgreed = true, lineAgreed = false)
        val trace = checkNotNull(line.decisionTrace)
        trace["lineArcsGstChargeAgreed"] shouldBe "NOT_AGREED"
        trace["counterpartyArcsGstChargeAgreed"] shouldBe "AGREED"
        trace["writtenAgreementSource"] shouldBe "LINE"
        val counterparty =
            checkNotNull(eval(fixture(), "ARSR", "ARCS", listOf("ARCS"), counterpartyAgreed = true).decisionTrace)
        counterparty["writtenAgreementSource"] shouldBe "COUNTERPARTY"
        val none = checkNotNull(eval(fixture(), "ARS", "ARCS", listOf("ARCS")).decisionTrace)
        none["writtenAgreementSource"] shouldBe "NONE"
    }

    @Test
    fun `INV-AC-2518 a released sub-flow row with parentScheme ACMT needs the ACMT election`() {
        val f = { fixture(scenario(releasedRecipient = true)) }
        eval(f(), "ACR", "AMCS").blockedWith("RUL-004")
        eval(f(), "ACR", null).blockedWith("RUL-004")
        eval(f(), "ACR", "ACMT").categoryCode shouldBe "SR"
    }

    @Test
    fun `INV-AC-2537 the electedSchemes array wins over election even when empty and the trace names the source`() {
        val both = eval(fixture(), "ARS", "ARCS", listOf("ARCS"), electedSchemes = listOf("ACMT", "ARCS"))
        both.treated() shouldBe Triple("NA", "0", arcs)
        both.decisionTrace?.get("elections") shouldBe "ACMT,ARCS"
        both.decisionTrace?.get("electionsSource") shouldBe "electedSchemes"
        eval(fixture(), "ARS", "ARCS", listOf("ARCS"), electedSchemes = listOf("ACMT")).blockedWith("RUL-004")
        val empty = eval(fixture(), "ARS", "ARCS", listOf("ARCS"), electedSchemes = emptyList())
        empty.blockedWith("RUL-004")
        empty.decisionTrace?.get("electionsSource") shouldBe "electedSchemes"
        val legacy = eval(fixture(), "ARS", "ARCS", listOf("ARCS"))
        legacy.treated() shouldBe Triple("NA", "0", arcs)
        legacy.decisionTrace?.get("electionsSource") shouldBe "election"
        eval(fixture(), "ARS", null, listOf("ARCS")).decisionTrace?.get("electionsSource") shouldBe "none"
    }

    @Test
    fun `INV-AC-2538 an NA line under the ARCS tag to a counterparty with no ARCS status is RUL-011`() {
        eval(fixture(), "ARS", "ARCS", emptyList()).blockedWith("RUL-011")
        eval(fixture(), "ARS", "ARCS", listOf("MES")).blockedWith("RUL-011")
    }

    @Test
    fun `INV-AC-2458 an ACMT disregarded service is not submitted and a recipient-accounting line is RUL-004`() {
        val acmt = eval(fixture(), "ACSVC", "ACMT")
        acmt.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        acmt.code shouldBe null
        eval(fixture(), "ACR", "ACMT").blockedWith("RUL-004")
    }

    @Test
    fun `INV-AC-2458 an ACMT suspended import is an excluded import permit and is not submitted`() {
        val r = eval(fixture(), "ACIMP", "ACMT", purchase = true)
        r.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.EXCLUDED_TXN
        r.code shouldBe null
    }

    @Test
    fun `INV-AC-2515 an ACMT recipient-accounting transaction blocks with RUL-004`() {
        eval(fixture(), "ACR", "ACMT").blockedWith("RUL-004")
    }

    @Test
    fun `INV-AC-2534 an ACMT overseas-client purchase blocks with RUL-004 and fills no code`() {
        eval(fixture(), "ACOC", "ACMT", purchase = true).blockedWith("RUL-004")
    }

    @Test
    fun `INV-AC-2533 an ACMT distributing agent's local sale is SR and submitted by the general rules`() {
        val r = eval(fixture(), "ACDA", "ACMT")
        r.categoryCode shouldBe "SR"
        r.rate?.toPlainString() shouldBe "9"
        r.scopeVerdict shouldBe inScope
        r.code shouldBe null
    }

    @Test
    fun `INV-AC-2302 a Cash Accounting purchase blocks with RUL-004 and is not submitted`() {
        eval(fixture(), "CAP", "CASH_ACCOUNTING", purchase = true).blockedWith("RUL-004")
    }

    @Test
    fun `INV-AC-2460 a Cash Accounting purchase and an AMCS tag without election both block with RUL-004`() {
        eval(fixture(), "CAP", "CASH_ACCOUNTING", purchase = true).blockedWith("RUL-004")
        val other = eval(fixture(), "AMS", "ARCS", listOf("AMCS"))
        other.blockedWith("RUL-004")
        other.decisionTrace?.get("outcome") shouldBe "SCHEME_NOT_ELECTED"
    }

    @Test
    fun `INV-AC-2499 a scheme line whose counterparty status is not recorded is RUL-011 and not submitted`() {
        eval(fixture(), "AMS", "AMCS", null).blockedWith("RUL-011")
    }

    @Test
    fun `INV-AC-2518 a tagged line of an entity that elects no scheme is RUL-004 naming the unelected scheme`() {
        val r = eval(fixture(), "SWSS", null)
        r.blockedWith("RUL-004")
        r.decisionTrace?.get("handler") shouldBe "SWS"
    }

    @Test
    fun `INV-AC-2517 an SWS transaction is handled as an ordinary ZR supply with no scheme data`() {
        val r = eval(fixture(), "SWSS", "SWS")
        r.categoryCode shouldBe "ZR"
        r.ibt022Statement shouldBe null
        r.code shouldBe null
        r.scopeVerdict shouldBe inScope
    }

    @Test
    fun `INV-AC-2449 an MES entity's local sale is SR at the prevailing rate with no scheme data`() {
        val r = eval(fixture(), "MESS", "MES")
        r.categoryCode shouldBe "SR"
        r.rate?.toPlainString() shouldBe "9"
        r.ibt022Statement shouldBe null
        r.decisionTrace shouldBe null
        r.scopeVerdict shouldBe inScope
    }

    @Test
    fun `INV-AC-2505 a flow blocked while pending is re-evaluated, not re-keyed, once the pack releases its handler`() {
        eval(fixture(scenario(pendingCash = true)), "CAP", "CASH_ACCOUNTING", purchase = true).blockedWith("RUL-004")
        val released = eval(fixture(scenario(pendingCash = false)), "CAP", "CASH_ACCOUNTING", purchase = true)
        released.code shouldBe null
        released.categoryCode shouldBe "TX"
    }

    // ---- INV-AC-2448: import permits and voluntary over-submission (VP-21) ----

    private val imports = listOf("IGDSIMP" to "IGDS", "MEIMP" to "ME", "IMIMP" to "IM")

    @Test
    fun `INV-AC-2448 an import mapped to IGDS, ME or IM is EXCLUDED_TXN with VP-21 off or absent`() {
        imports.forEach { (internal, iras) ->
            val absent = eval(fixture(), internal, "IGDS", purchase = true)
            val off = fixture().also { vp21(it, "OFF") }.let { eval(it, internal, "IGDS", purchase = true) }
            listOf(absent, off).forEach {
                it.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.EXCLUDED_TXN
                it.categoryCode shouldBe iras
                it.code shouldBe null
            }
        }
    }

    @Test
    fun `INV-AC-2448 with VP-21 ON an import mapped to IGDS, ME or IM is SUBMITTABLE with its code`() {
        imports.forEach { (internal, iras) ->
            val r = fixture().also { vp21(it, "ON") }.let { eval(it, internal, "IGDS", purchase = true) }
            r.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.SUBMITTABLE
            r.categoryCode shouldBe iras
            r.code shouldBe null
            r.severity shouldBe null
        }
    }

    @Test
    fun `INV-AC-2448 the ON value of another entity, another key or another word is not applied`() {
        listOf(
            fixture().also { vp21(it, "ON", entity = "E2") },
            fixture().also { vp21(it, "ON", key = "VP-22") },
            fixture().also { vp21(it, "on") },
            fixture().also { vp21(it, "ON ") },
        ).forEach {
            eval(it, "IGDSIMP", "IGDS", purchase = true).scopeVerdict shouldBe
                EvaluationResult.ScopeVerdict.EXCLUDED_TXN
        }
    }

    @Test
    fun `INV-AC-2448 a look-alike code IGD or ME2 is not an import permit code even with VP-21 ON`() {
        listOf("LOOKIGD", "LOOKME").forEach {
            val r = fixture().also { f -> vp21(f, "ON") }.let { f -> eval(f, it, "IGDS", purchase = true) }
            r.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.EXCLUDED_TXN
        }
    }
}
