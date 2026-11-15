package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.events.PackEventMapper
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
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
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-22908: every item of `packVersionsApplied` carries the `packIdentifier` of the pack version it names, the
 * `packId` of the registry's manifest, the value `RulePackStaged` and `RulePackActivated` carry. The packs sit in the
 * kernel registry; all pack data is invented.
 */
class PackIdentifierAnswerTest {
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
        packId: String,
        label: String,
    ) = PackVersion(PackManifest(PackId(packId), type, PackVersionLabel(label), start, null, "1.0.0"))

    private val scopeVersion = version(PackTypes.SCOPE, "gst-scope", "2026.3")
    private val mappingVersion = version(PackTypes.MAPPING, "MAP-0042", "7")
    private val catalogueVersion = version(PackTypes.CODE_CATALOGUE, "CAT-0003", "c1")
    private val scenarioVersion = version(PackTypes.SCENARIO, "SCN-0001", "s1")

    private val contents =
        mapOf(
            scopeVersion to scopePack,
            mappingVersion to mapping,
            catalogueVersion to catalogue,
            scenarioVersion to scenario,
        )

    private fun profiledStructure(): StructureReplica {
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
        return structure
    }

    private fun controller(): EvaluationController {
        val registry = InMemoryPackRegistry()
        contents.keys.forEach(registry::register)
        val lookup = PackLookup { type, owner, date -> registry.resolve(type, owner, date) }
        val artefacts = { v: PackVersion ->
            contents[v]?.let { PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), it.toByteArray()) }
        }
        val reader = PackContentsReader(lookup, artefacts)
        val structure = profiledStructure()
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

    private fun answer(extra: String): EvaluationResult =
        checkNotNull(
            controller()
                .evaluateDocument(
                    "flow-context",
                    mapper.readValue(
                        """{"entityRef":"E1","transactionDate":"2026-04-01","internalTaxCode":"SR"$extra}""",
                        EvaluateDocumentRequest::class.java,
                    ),
                ).body,
        )

    private val classified = ""","documentFunction":"BILL_FOR_PAYMENT","documentKind":"INVOICE","lineSign":"POSITIVE""""
    private val unclassified = ""","documentFunction":"BILL_FOR_PAYMENT""""

    private val byType =
        mapOf(
            "SCOPE" to scopeVersion,
            "MAPPING" to mappingVersion,
            "CODE_CATALOGUE" to catalogueVersion,
            "SCENARIO" to scenarioVersion,
        )

    @Test
    fun `INV-AC-22908 a each item of a verdict answer carries the packId of the version it names`() {
        val items = answer(classified).packVersionsApplied.orEmpty()
        items.map { it.packType }.toSet() shouldBe byType.keys
        items.forEach { item ->
            val expected = byType.getValue(item.packType)
            item.version shouldBe expected.version.value
            item.packIdentifier shouldBe expected.manifest.packId.value
        }
        items.single { it.packType == "SCOPE" }.packIdentifier shouldBe "gst-scope"
        items.single { it.packType == "MAPPING" }.packIdentifier shouldBe "MAP-0042"
    }

    @Test
    fun `INV-AC-22908 b the scope item of a refusal answer carries the packIdentifier`() {
        val result = answer(unclassified)
        result.code shouldBe "RUL-012"
        val scope = result.packVersionsApplied.orEmpty().single { it.packType == "SCOPE" }
        scope.version shouldBe "2026.3"
        scope.packIdentifier shouldBe "gst-scope"
    }

    @Test
    fun `INV-AC-22908 c the SCOPE packIdentifier joins to the RulePackActivated packIdentifier, each type`() {
        val items = answer(classified).packVersionsApplied.orEmpty()
        byType.forEach { (type, registeredVersion) ->
            val registered = registeredVersion.manifest
            val activated =
                PackEventMapper().map(
                    PackEvent.RulePackActivated(
                        registered.packId.value,
                        registered.version.value,
                        registered.effectiveFrom,
                    ),
                )
            val published = mapper.readTree(activated.dataJson).get("packIdentifier").asString()
            items.single { it.packType == type }.packIdentifier shouldBe published
        }
        items.single { it.packType == "SCOPE" }.packIdentifier shouldBe "gst-scope"
    }

    @Test
    fun `INV-AC-22908 d no item has the pack type or the version as its identifier`() {
        val items =
            answer(classified).packVersionsApplied.orEmpty() +
                answer(unclassified).packVersionsApplied.orEmpty()
        items.forEach { item ->
            item.packIdentifier shouldNotBe null
            item.packIdentifier shouldNotBe item.packType
            item.packIdentifier shouldNotBe item.version
        }
    }
}
