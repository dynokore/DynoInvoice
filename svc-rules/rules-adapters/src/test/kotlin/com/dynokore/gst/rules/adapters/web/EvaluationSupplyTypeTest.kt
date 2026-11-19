package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.PackContentsFailure
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.domain.coding.CatalogueEntry
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-24844: the `supplyType` member of the `evaluateDocument` answer is the `supplyType` of the entry of the
 * answered `categoryCode` in the code catalogue pack version in force at the transaction date; absent when the entry names none
 * or the code has no entry; a value outside the five members is an unreadable pack (PackContentsFailure), never copied.
 * The real controller, use case and pack reader run over in-memory packs with invented data.
 */
class EvaluationSupplyTypeTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val start = LocalDate.of(2026, 1, 1)
    private val change = LocalDate.of(2026, 4, 1)
    private val flow = "eyJhbGciOiJub25lIn0.eyJmYyI6InQifQ.c2ln"

    private fun mapping(vararg pairs: Pair<String, String>) =
        """{"entries":[""" +
            pairs.joinToString(",") {
                """{"entryId":"e-${it.first}","internalTaxCode":"${it.first}","irasCode":"${it.second}",""" +
                    """"sourceClass":"PRIMARY"}"""
            } + "]}"

    private fun code(
        code: String,
        supplyType: String?,
    ) = """{"code":"$code","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
        """"sourceClass":"PRIMARY","sourceReference":"i"""" +
        supplyType?.let { ""","supplyType":"$it"""" }.orEmpty() + "}"

    private fun catalogue(vararg codes: String) = """{"complete":true,"codes":[${codes.joinToString(",")}]}"""

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
        """{"classificationTable":[],"lineScopeRules":[""" +
            """{"categoryCode":"SR","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"t"},""" +
            """{"categoryCode":"NA","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"t"},""" +
            """{"categoryCode":"ZR","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"t"}],""" +
            """"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    private fun version(
        type: PackType,
        label: String,
        from: LocalDate,
    ) = PackVersion(PackManifest(PackId("$label-id"), type, PackVersionLabel(label), from, null, "1.0.0"))

    /** Catalogue contents by version: [old] is in force before 2026-04-01, [current] from it on. */
    @Suppress("LongMethod") // one wiring of the real controller over in-memory packs and replicas
    private fun controller(
        current: String,
        old: String = current,
        mapped: Array<Pair<String, String>> = arrayOf("SR" to "SR", "NA" to "NA", "ZR" to "ZR"),
    ): EvaluationController {
        val cOld = version(PackTypes.CODE_CATALOGUE, "c1", start)
        val cNew = version(PackTypes.CODE_CATALOGUE, "c2", change)
        val held =
            mapOf(
                version(PackTypes.MAPPING, "m1", start) to mapping(*mapped),
                cOld to old,
                cNew to current,
                version(PackTypes.SCENARIO, "s1", start) to scenario,
                version(PackTypes.SCOPE, "sc1", start) to scopePack,
            )
        val lookup =
            PackLookup { type, _, date ->
                held.keys
                    .filter { it.manifest.packType == type && !date.isBefore(it.manifest.effectiveFrom) }
                    .maxByOrNull { it.manifest.effectiveFrom }
            }
        val artefacts = { v: PackVersion ->
            held[v]?.let { PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), it.toByteArray()) }
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
        internalCode: String,
        date: String = "2026-05-01",
        draft: Boolean = false,
    ): EvaluationResult {
        val request =
            mapper.readValue(
                """{"entityRef":"E1","transactionDate":"$date","internalTaxCode":"$internalCode"}""",
                EvaluateDocumentRequest::class.java,
            )
        return checkNotNull(
            (if (draft) c.evaluatePreIssuanceDraft(flow, request) else c.evaluateDocument(flow, request)).body,
        )
    }

    private val both =
        catalogue(code("SR", "ZERO_RATED"), code("NA", "EXEMPT"), code("ZR", null))

    @Test
    fun `INV-AC-24844 an entry with supplyType ZERO_RATED gives ZERO_RATED on both operations`() {
        val c = controller(both)
        answer(c, "SR").supplyType shouldBe EvaluationResult.SupplyType.ZERO_RATED
        answer(c, "SR", draft = true).supplyType shouldBe EvaluationResult.SupplyType.ZERO_RATED
    }

    @Test
    fun `INV-AC-24844 an entry without supplyType gives no member`() {
        val r = answer(controller(both), "ZR")
        r.categoryCode shouldBe "ZR"
        r.supplyType shouldBe null
    }

    @Test
    fun `INV-AC-24844 the version in force at the transaction date decides, not a newer or older one`() {
        val old = catalogue(code("SR", "EXEMPT"), code("NA", "EXEMPT"), code("ZR", null))
        val c = controller(both, old)
        answer(c, "SR", date = "2026-03-31").supplyType shouldBe EvaluationResult.SupplyType.EXEMPT
        answer(c, "SR", date = "2026-04-01").supplyType shouldBe EvaluationResult.SupplyType.ZERO_RATED
    }

    @Test
    fun `INV-AC-24844 look-alike codes have no entry and answer no supplyType`() {
        val entry = CatalogueEntry("SR", "G", "SUPPLY", "9", null, null, null, "ZERO_RATED")
        val catalogue = CodeCatalogue(true, listOf(entry))
        catalogue.entryOf("SR ") shouldBe null
        catalogue.entryOf("sr") shouldBe null
        catalogue.entryOf("SR")?.supplyType shouldBe "ZERO_RATED"
        // the mapping pack decoder admits only bare codes, so a look-alike never reaches the lookup
        val c = controller(both, mapped = arrayOf("TS" to "SR ", "LS" to "sr"))
        listOf("TS", "LS").forEach {
            shouldThrow<PackContentsFailure> { answer(c, it) }.field shouldBe "$.entries[0].irasCode"
        }
    }

    @Test
    fun `INV-AC-24844 a swapped value fails, the supplyType is that of the answered code only`() {
        val c = controller(both)
        val sr = answer(c, "SR")
        val na = answer(c, "NA")
        sr.supplyType shouldBe EvaluationResult.SupplyType.ZERO_RATED
        na.supplyType shouldBe EvaluationResult.SupplyType.EXEMPT
        (sr.supplyType == na.supplyType) shouldBe false
    }

    @Test
    fun `INV-AC-24844 the member is absent, never null, in the serialized JSON`() {
        val json = mapper.writeValueAsString(answer(controller(both), "ZR"))
        json shouldNotContain "supplyType"
        mapper.writeValueAsString(answer(controller(both), "SR")) shouldContain "\"supplyType\":\"ZERO_RATED\""
    }

    @Test
    fun `INV-AC-24844 a value outside the five members fails the evaluation as an unreadable pack`() {
        listOf("zero_rated", "EXEMPT ", "", "OTHER").forEach {
            val bad = catalogue(code("SR", it))
            shouldThrow<PackContentsFailure> { answer(controller(bad), "SR") }.field shouldBe "$.codes[0].supplyType"
        }
    }
}
