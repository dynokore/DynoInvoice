package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
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

private const val POLICY_TOPIC = "gst.platform.policy-changed.v1"
private const val SIX_MEMBERS = "the six ScopeVerdict members"

/**
 * `evaluateDocument` at the service boundary over the real controller, use case, pack reader and the production
 * [PackScopeVerdictPort]; the packs are held in memory and the replicas are fed with the events of their feeds. All
 * pack data is invented. Each requirement key sits in the name of the test that proves it.
 */
class ScopeVerdictBoundaryTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val start = LocalDate.of(2026, 1, 1)

    private fun entry(
        id: String,
        internal: String,
        iras: String,
    ) = """{"entryId":"$id","internalTaxCode":"$internal","irasCode":"$iras","sourceClass":"PRIMARY"}"""

    private fun code(
        code: String,
        group: String,
        side: String,
    ) = """{"code":"$code","group":"$group","side":"$side","annexERate":"9","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    private val mapping =
        listOf("SR", "OS", "DS", "SRRC", "OP", "ZZ")
            .mapIndexed { i, c -> entry("e$i", c, c) }
            .joinToString(",", """{"entries":[""", "]}")
    private val catalogue =
        listOf(
            code("SR", "SUPPLY_STANDARD_RATED", "SUPPLY"),
            code("OS", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY"),
            code("DS", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY"),
            code("SRRC", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE"),
            code("OP", "PURCHASE_STANDARD_RATED", "PURCHASE"),
            code("ZZ", "SUPPLY_STANDARD_RATED", "SUPPLY"),
        ).joinToString(",", """{"complete":true,"codes":[""", "]}")
    private val scenario =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},"schemeTagSides":[{""" +
            """"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY","sourceReference":"s"}],""" +
            """"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"SR","sourceClass":"PRIMARY",""" +
            """"sourceReference":"s","rateBasis":"FIXED","rate":"9"}],"approvableSchemes":[{"scheme":"MES",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s"}]}"""

    private fun rule(
        c: String,
        v: String,
    ) = """{"categoryCode":"$c","lineVerdict":"$v","sourceClass":"PRIMARY","sourceReference":"t"}"""

    private val scopePack =
        """{"classificationTable":[{"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"INVOICE",""" +
            """"signOfTotal":"POSITIVE","documentFunction":"BILL_FOR_PAYMENT","documentTypeCode":"380",""" +
            """"signNormalised":false,"sourceClass":"PRIMARY","sourceReference":"t"}],"lineScopeRules":[""" +
            listOf(
                rule("SR", "IN_SCOPE"),
                rule("OS", "OUT_OF_SCOPE"),
                rule("DS", "EXCLUDED_TXN"),
                rule("SRRC", "EXCLUDED_TXN"),
                rule("OP", "IN_SCOPE"),
                rule("ZZ", "IN_SCOPE"),
            ).joinToString(",") +
            """],"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    private fun version(
        type: PackType,
        label: String,
    ) = PackVersion(PackManifest(PackId("$label-id"), type, PackVersionLabel(label), start, null, "1.0.0"))

    private val versions =
        mapOf(
            PackTypes.MAPPING to (version(PackTypes.MAPPING, "m1") to mapping),
            PackTypes.CODE_CATALOGUE to (version(PackTypes.CODE_CATALOGUE, "c1") to catalogue),
            PackTypes.SCENARIO to (version(PackTypes.SCENARIO, "s1") to scenario),
            PackTypes.SCOPE to (version(PackTypes.SCOPE, "sc1") to scopePack),
        )

    private class Fixture(
        val controller: EvaluationController,
        val structure: StructureReplica,
        val policy: PolicyReplica,
    )

    private fun fixture(
        withScopePack: Boolean = true,
        port: (PackScopeVerdictPort) -> ScopeVerdictPort = { it },
    ): Fixture {
        val held = versions.filterKeys { withScopePack || it != PackTypes.SCOPE }
        val lookup = PackLookup { type, _, date -> held[type]?.first?.takeIf { !date.isBefore(start) } }
        val artefacts = { v: PackVersion ->
            held.values.firstOrNull { it.first == v }?.second?.let {
                PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), it.toByteArray())
            }
        }
        val reader = PackContentsReader(lookup, artefacts)
        val structure = StructureReplica(clock)
        val policy = PolicyReplica(clock, setOf(POLICY_TOPIC))
        val scope = port(PackScopeVerdictPort(lookup, artefacts, reader))
        val evaluate =
            EvaluateDocument(
                PackResolver(lookup),
                Evaluator(emptyList()),
                emptyList(),
                DocumentCoder(reader, reader, reader),
                scope,
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
            )
        return Fixture(controller, structure, policy)
    }

    private fun profile(
        f: Fixture,
        entity: String = "E1",
        vararg more: Pair<String, String>,
    ) = f.structure.accept(
        EntityProfileChanged(
            tenant,
            entity,
            StructureNode(
                StructureNodeKind.ENTITY,
                null,
                false,
                "E",
                "E1",
                (listOf("obligationStartDate" to "2026-03-01") + more).map { ProfileEntry(it.first, it.second, start) },
                1,
            ),
        ),
    )

    private fun election(
        f: Fixture,
        value: String,
        entity: String = "E1",
        vp: String = "VP-21",
        offset: Long = 1,
    ) = f.policy.accept(
        PolicyChanged(
            PolicyScope.entity(tenant, entity),
            vp,
            value,
            start,
            PolicySource.PROFILE,
            null,
            POLICY_TOPIC,
            offset,
        ),
    )

    private fun request(
        code: String,
        date: String = "2026-04-01",
        extra: String = "",
        entity: String = "E1",
    ): EvaluateDocumentRequest =
        mapper.readValue(
            """{"entityRef":"$entity","transactionDate":"$date","internalTaxCode":"$code"$extra}""",
            EvaluateDocumentRequest::class.java,
        )

    private fun evaluate(
        f: Fixture,
        code: String,
        date: String = "2026-04-01",
        extra: String = "",
        entity: String = "E1",
    ): EvaluationResult = checkNotNull(f.controller.evaluateDocument(request(code, date, extra, entity)).body)

    private val purchase = ""","direction":"PURCHASE""""

    private fun both(
        code: String,
        extra: String = "",
        entity: String = "E1",
    ): Pair<EvaluationResult, EvaluationResult> {
        val off = fixture().also { profile(it) }
        val on =
            fixture().also {
                profile(it)
                election(it, "ON")
            }
        return evaluate(off, code, extra = extra, entity = entity) to evaluate(on, code, extra = extra, entity = entity)
    }

    private fun EvaluationResult.pair() = scopeVerdict to categoryCode

    // ---- default mode ----

    @Test
    fun `INV-REQ-493 an SR supply line is IN_SCOPE and an OS supply line OUT_OF_SCOPE`() {
        val f = fixture().also { profile(it) }
        evaluate(f, "SR").pair() shouldBe (EvaluationResult.ScopeVerdict.IN_SCOPE to "SR")
        evaluate(f, "OS").pair() shouldBe (EvaluationResult.ScopeVerdict.OUT_OF_SCOPE to "OS")
    }

    @Test
    fun `INV-AC-513 INV-AC-523 a DS supply line is NOT_SUBMITTABLE and the line is still coded, not blocked`() {
        val answer = evaluate(fixture().also { profile(it) }, "DS")
        answer.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.NOT_SUBMITTABLE
        answer.categoryCode shouldBe "DS"
        answer.code shouldBe null
        answer.severity shouldBe null
    }

    @Test
    fun `INV-AC-514 INV-REQ-496 an SRRC line is EXCLUDED_TXN`() {
        evaluate(fixture().also { profile(it) }, "SRRC", extra = purchase).pair() shouldBe
            (EvaluationResult.ScopeVerdict.EXCLUDED_TXN to "SRRC")
    }

    @Test
    fun `INV-AC-388 an unclaimed standard-rated purchase is OUT_OF_SCOPE and a claimed one IN_SCOPE`() {
        val f = fixture().also { profile(it) }
        evaluate(f, "OP", extra = purchase + ""","inputTaxClaimedNonZero":false""").scopeVerdict shouldBe
            EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        evaluate(f, "OP", extra = purchase + ""","inputTaxClaimedNonZero":true""").scopeVerdict shouldBe
            EvaluationResult.ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-REQ-378 a date before obligationStartDate is PRE_OBLIGATION without code and severity`() {
        val answer = evaluate(fixture().also { profile(it) }, "SR", date = "2026-02-28")
        answer.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.PRE_OBLIGATION
        answer.code shouldBe null
        answer.severity shouldBe null
    }

    @Test
    fun `INV-REQ-377 a date after registrationEndDate is NOT_SUBMITTABLE and the last day is in scope`() {
        val f = fixture().also { profile(it, "E1", "registrationEndDate" to "2026-09-30") }
        val after = evaluate(f, "SR", date = "2026-10-01")
        after.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.NOT_SUBMITTABLE
        after.code shouldBe null
        after.severity shouldBe null
        evaluate(f, "SR", date = "2026-09-30").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-REQ-378 an entity without a profile entry is PRE_OBLIGATION, never a guess`() {
        evaluate(fixture(), "SR").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.PRE_OBLIGATION
    }

    // ---- voluntary over-submission ----

    @Test
    fun `INV-AC-396 INV-AC-384 INV-AC-2268 an OS line is OUT_OF_SCOPE by default, SUBMITTABLE with VP-21 ON`() {
        val (off, on) = both("OS")
        off.pair() shouldBe (EvaluationResult.ScopeVerdict.OUT_OF_SCOPE to "OS")
        on.pair() shouldBe (EvaluationResult.ScopeVerdict.SUBMITTABLE to "OS")
    }

    @Test
    fun `INV-AC-1506 INV-AC-523 with VP-21 ON the OS line is SUBMITTABLE and the DS line stays NOT_SUBMITTABLE`() {
        val (offOs, onOs) = both("OS")
        val (offDs, onDs) = both("DS")
        onOs.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.SUBMITTABLE
        offOs.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        onDs.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.NOT_SUBMITTABLE
        offDs.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.NOT_SUBMITTABLE
    }

    @Test
    fun `INV-AC-388 INV-REQ-375 with VP-21 ON an unclaimed standard-rated purchase is SUBMITTABLE with OP`() {
        val (off, on) = both("OP", purchase + ""","inputTaxClaimedNonZero":false""")
        off.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        on.pair() shouldBe (EvaluationResult.ScopeVerdict.SUBMITTABLE to "OP")
    }

    @Test
    fun `INV-REQ-496 with VP-21 ON an SRRC line stays EXCLUDED_TXN`() {
        val (off, on) = both("SRRC", purchase)
        off.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.EXCLUDED_TXN
        on.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.EXCLUDED_TXN
    }

    @Test
    fun `INV-AC-2268 the replica holding OFF, or the look-alike key VP-22 or VP-210, gives the OFF verdict`() {
        listOf(Triple("OFF", "E1", "VP-21"), Triple("ON", "E1", "VP-22"), Triple("ON", "E1", "VP-210")).forEach {
            val f =
                fixture().also { fx ->
                    profile(fx)
                    election(fx, it.first, it.second, it.third)
                }
            evaluate(f, "OS").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        }
    }

    @Test
    fun `INV-AC-2268 the ON value of entity E1 is not applied to entity E2`() {
        val f =
            fixture().also {
                profile(it)
                profile(it, "E2")
                election(it, "ON", "E1")
            }
        evaluate(f, "OS", entity = "E1").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.SUBMITTABLE
        evaluate(f, "OS", entity = "E2").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-2268 a PolicyChanged for VP-21 carrying YES, on or ON with a blank is not applied`() {
        listOf("YES", "on", "ON ", "true", "").forEach {
            val f =
                fixture().also { fx ->
                    profile(fx)
                    election(fx, it)
                }
            evaluate(f, "OS").scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        }
    }

    // ---- intra-group ----

    @Test
    fun `INV-AC-2783 INV-AC-761 sameGroup true with an SR line carries the warning, absent request key gives none`() {
        val f = fixture().also { profile(it) }
        val flagged = evaluate(f, "SR", extra = ""","sameGroup":true""")
        flagged.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.IN_SCOPE
        flagged.disregardedSupplyWarning shouldBe request("SR", extra = ""","sameGroup":true""").sameGroup
        evaluate(f, "SR").disregardedSupplyWarning shouldBe null
    }

    @Test
    fun `INV-AC-761 sameGroup true with an OS line is OUT_OF_SCOPE and carries no warning`() {
        val answer = evaluate(fixture().also { profile(it) }, "OS", extra = ""","sameGroup":true""")
        answer.scopeVerdict shouldBe EvaluationResult.ScopeVerdict.OUT_OF_SCOPE
        answer.disregardedSupplyWarning shouldBe null
    }

    @Test
    fun `the look-alike request key samegroup is ignored and the response carries no warning`() {
        val answer = evaluate(fixture().also { profile(it) }, "SR", extra = ""","samegroup":true""")
        answer.disregardedSupplyWarning shouldBe null
    }

    // ---- no scope pack, and the six members ----

    @Test
    fun `a date with no armed scope pack is the RUL-002 refusal`() {
        val f = fixture(withScopePack = false).also { profile(it) }
        val answer = evaluate(f, "SR")
        answer.code shouldBe "RUL-002"
        answer.severity shouldBe EvaluationResult.Severity.BLOCK
        answer.categoryCode shouldBe null
    }

    @Test
    fun `INV-REQ-493 the response scopeVerdict equals the value the port returned for each of the six members`() {
        ScopeVerdict.entries.size shouldBe 6
        ScopeVerdict.entries.forEach { member ->
            val seen = mutableListOf<ScopeFacts>()
            val f =
                fixture(port = { _ ->
                    object : ScopeVerdictPort {
                        override fun verdictOf(facts: ScopeFacts) = member

                        override fun outcomeOf(facts: ScopeFacts): ScopeOutcome {
                            seen += facts
                            return ScopeOutcome(member, disregardedSupplyWarning = false)
                        }
                    }
                })
            evaluate(f, "SR").scopeVerdict.name shouldBe member.name
            seen.size shouldBe 1
        }
        SIX_MEMBERS.isNotEmpty() shouldBe true
    }

    @Test
    fun `the controller hands the request facts to the port`() {
        val seen = mutableListOf<ScopeFacts>()
        val f =
            fixture(port = {
                object : ScopeVerdictPort {
                    override fun verdictOf(facts: ScopeFacts) = ScopeVerdict.IN_SCOPE

                    override fun outcomeOf(facts: ScopeFacts): ScopeOutcome {
                        seen += facts
                        return ScopeOutcome(ScopeVerdict.IN_SCOPE, disregardedSupplyWarning = false)
                    }
                }
            })
        profile(f)
        election(f, "ON")
        evaluate(f, "OP", extra = purchase + ""","inputTaxClaimedNonZero":false,"sameGroup":true""")
        val facts = seen.single()
        facts.sameGroup shouldBe true
        facts.inputTaxClaimedNonZero shouldBe false
        facts.direction.name shouldBe "PURCHASE"
        facts.voluntaryOverSubmission shouldBe true
        facts.obligationStartDate?.value shouldBe LocalDate.of(2026, 3, 1)
        evaluate(f, "SR")
        seen.last().sameGroup shouldBe false
        seen.last().inputTaxClaimedNonZero shouldBe null
        seen.last().direction.name shouldBe "SALE"
    }
}
