package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.SubmissionType
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The decoders against the schemas of the pinned bundle (the `contract/schemas` resources, INV-Q-11614): every sample
 * is judged by the schema itself and by the decoder, and the two must agree. The bundle ships no examples, so the
 * samples are written here, one per rule of the schema, after the sample names of platform-contracts
 * `rules/examples` (reported as a gap in the MR).
 */
class PackContentsDecodeTest {
    private val registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    private fun schemaAccepts(
        file: String,
        json: String,
    ): Boolean {
        val text =
            checkNotNull(PackContentsDecodeTest::class.java.getResourceAsStream("/contract/schemas/$file")) {
                "the contract schema $file is not in the pinned bundle"
            }.use { String(it.readAllBytes(), Charsets.UTF_8) }
        return registry.getSchema(text).validate(json, InputFormat.JSON).isEmpty()
    }

    private fun decodes(
        decode: (ByteArray) -> Any,
        json: String,
    ): Boolean =
        try {
            decode(json.toByteArray())
            true
        } catch (_: PackContentsFailure) {
            false
        }

    private fun entry(
        id: String = "e1",
        code: String = "SR-STD",
        iras: String = "SR",
        extra: String = "",
    ) = """{"entryId":"$id","internalTaxCode":"$code","irasCode":"$iras","sourceClass":"PRIMARY"$extra}"""

    private fun mapping(vararg entries: String) = """{"entries":[${entries.joinToString(",")}]}"""

    private val mappingSamples: Map<String, String> =
        mapOf(
            "valid minimum" to mapping(entry()),
            "valid bl entry" to mapping(entry(iras = "BL")),
            "valid scheme tag and citation" to
                mapping(entry(extra = ""","schemeTag":"SELF_BILLING","sourceReference":"INV-NOTE-2"""")),
            "valid unknown properties" to """{"entries":[${entry(extra = ""","x":1""")}],"y":{}}""",
            "valid secondary accepted" to
                mapping(
                    entry(
                        extra =
                            ""","accepted":true,"acceptance":{"rationale":"r","expiresOn":"2027-01-01",""" +
                                """"acceptedBy":"u1"}""",
                    ).replace("PRIMARY", "SECONDARY"),
                ),
            "invalid entries missing" to "{}",
            "invalid entries empty" to """{"entries":[]}""",
            "invalid entries object" to """{"entries":{}}""",
            "invalid iras code namespaced" to mapping(entry(iras = "gst:SR")),
            "invalid iras code lower case" to mapping(entry(iras = "sr")),
            "invalid iras code 11 chars" to mapping(entry(iras = "ABCDEFGHIJK")),
            "invalid iras code missing" to
                """{"entries":[{"entryId":"e","internalTaxCode":"X","sourceClass":"PRIMARY"}]}""",
            "invalid internal tax code 41 chars" to mapping(entry(code = "A".repeat(41))),
            "invalid internal tax code empty" to mapping(entry(code = "")),
            "invalid entry id 41 chars" to mapping(entry(id = "A".repeat(41))),
            "invalid entry id empty" to mapping(entry(id = "")),
            "invalid source class lower case" to mapping(entry().replace("PRIMARY", "primary")),
            "invalid source class missing" to
                """{"entries":[{"entryId":"e","internalTaxCode":"X","irasCode":"SR"}]}""",
            "invalid source reference empty" to mapping(entry(extra = ""","sourceReference":""""")),
            "invalid scheme tag lower case" to mapping(entry(extra = ""","schemeTag":"self"""")),
            "invalid accepted string" to mapping(entry(extra = ""","accepted":"true"""")),
            "invalid secondary accepted without acceptance" to
                mapping(entry(extra = ""","accepted":true""").replace("PRIMARY", "SECONDARY")),
            "invalid acceptance rationale missing" to
                mapping(
                    entry(extra = ""","acceptance":{"expiresOn":"2027-01-01","acceptedBy":"u"}"""),
                ),
            "invalid acceptance expires not a date" to
                mapping(
                    entry(extra = ""","acceptance":{"rationale":"r","expiresOn":"soon","acceptedBy":"u"}"""),
                ),
            "invalid acceptance accepted by empty" to
                mapping(
                    entry(extra = ""","acceptance":{"rationale":"r","expiresOn":"2027-01-01","acceptedBy":""}"""),
                ),
        )

    private fun catalogueCode(
        code: String = "SR",
        group: String = "SUPPLY_STANDARD_RATED",
        side: String = "SUPPLY",
        rate: String = "9",
        extra: String = "",
    ) = """{"code":"$code","group":"$group","side":"$side","annexERate":"$rate","sourceClass":"PRIMARY",""" +
        """"sourceReference":"p79"$extra}"""

    private fun catalogue(
        vararg codes: String,
        complete: String = "true",
    ) = """{"complete":$complete,"codes":[${codes.joinToString(",")}]}"""

    private val catalogueSamples: Map<String, String> =
        mapOf(
            "valid minimum" to catalogue(catalogueCode(), complete = "false"),
            "valid na with submission rate" to
                catalogue(
                    catalogueCode(
                        "NA",
                        "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE",
                        rate = "NA",
                        extra = ""","submissionRate":"0"""",
                    ),
                ),
            "valid suffix code" to catalogue(catalogueCode("SRCA-C")),
            "valid unknown properties" to catalogue(catalogueCode(extra = ""","x":1""")).dropLast(1) + ""","y":2}""",
            "invalid complete missing" to """{"codes":[${catalogueCode()}]}""",
            "invalid codes empty" to """{"complete":true,"codes":[]}""",
            "invalid code lower case" to catalogue(catalogueCode("sr")),
            "invalid code namespaced" to catalogue(catalogueCode("gst:SR")),
            "invalid code 11 chars" to catalogue(catalogueCode("ABCDEFGHIJK")),
            "invalid code hyphen no suffix" to catalogue(catalogueCode("SR-")),
            "invalid rate percent" to catalogue(catalogueCode(rate = "9%")),
            "invalid rate five decimals" to catalogue(catalogueCode(rate = "9.12345")),
            "invalid rate lower case" to catalogue(catalogueCode(rate = "na")),
            "invalid group unknown" to catalogue(catalogueCode(group = "OTHER")),
            "invalid supply group on purchase side" to catalogue(catalogueCode(side = "PURCHASE")),
            "invalid purchase group on supply side" to
                catalogue(catalogueCode("TX", "PURCHASE_STANDARD_RATED", "SUPPLY")),
            "invalid submission rate na" to catalogue(catalogueCode(extra = ""","submissionRate":"NA"""")),
        )

    private val party =
        """{"direction":"PURCHASE","submissionType":"TYPE_3","sourceClass":"INFERRED","sourceReference":"s"}"""
    private val supplierParty =
        """{"direction":"SALE","submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}"""
    private val tagRow =
        """{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY","sourceReference":"s"}"""
    private val approvable = """[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]"""

    private fun treatmentJson(extra: String) =
        """{"tag":"A3PL","side":"SUPPLY","categoryCode":"NA","sourceClass":"PRIMARY","sourceReference":"s",$extra}"""

    private fun scenario(
        assignment: String = """{"customerCumIssuer":$party,"supplierCumRecipient":$supplierParty}""",
        tags: String = "[$tagRow]",
        treatments: String = "[${treatmentJson(""""rateBasis":"FIXED","rate":"0"""")}]",
    ) = """{"selfBillingAssignment":$assignment,"schemeTagSides":$tags,"treatments":$treatments,""" +
        """"approvableSchemes":$approvable}"""

    private val scenarioSamples: Map<String, String> =
        mapOf(
            "valid minimum" to scenario(),
            "valid prevailing without rate" to
                scenario(treatments = "[${treatmentJson(""""rateBasis":"PREVAILING"""")}]"),
            "valid six decimals" to
                scenario(treatments = "[${treatmentJson(""""rateBasis":"FIXED","rate":"9.123456"""")}]"),
            "valid approved under and agreement" to
                scenario(
                    treatments =
                        "[${treatmentJson(
                            """"rateBasis":"FIXED","rate":"0","counterpartyApprovedUnder":["MES","A3PL"],""" +
                                """"writtenAgreement":"AGREED","ibt022Statement":"stmt"""",
                        )}]",
                ),
            "valid pending question" to
                scenario(
                    tags =
                        """[{"tag":"CASH_ACCOUNTING","side":"PURCHASE","handling":"PENDING_QUESTION",""" +
                            """"pendingReference":"Q-4","sourceClass":"PRIMARY","sourceReference":"s"}]""",
                ),
            "valid unknown properties" to scenario().dropLast(1) + ""","x":1}""",
            "invalid assignment missing supplier" to scenario(assignment = """{"customerCumIssuer":$party}"""),
            "invalid submission type unknown" to
                scenario(
                    assignment =
                        """{"customerCumIssuer":${party.replace(
                            "TYPE_3",
                            "TYPE_9",
                        )},"supplierCumRecipient":$supplierParty}""",
                ),
            "invalid direction lower case" to
                scenario(
                    assignment =
                        """{"customerCumIssuer":${party.replace(
                            "PURCHASE",
                            "purchase",
                        )},"supplierCumRecipient":$supplierParty}""",
                ),
            "invalid tag lower case" to scenario(tags = "[${tagRow.replace("A3PL", "a3pl")}]"),
            "invalid tag side empty" to scenario(tags = "[]"),
            "invalid pending without reference" to
                scenario(tags = "[${tagRow.replace("HANDLED", "PENDING_QUESTION")}]"),
            "invalid handled with reference" to
                scenario(tags = "[${tagRow.dropLast(1)},\"pendingReference\":\"Q\"}]"),
            "invalid fixed without rate" to scenario(treatments = "[${treatmentJson(""""rateBasis":"FIXED"""")}]"),
            "invalid prevailing with rate" to
                scenario(treatments = "[${treatmentJson(""""rateBasis":"PREVAILING","rate":"9"""")}]"),
            "invalid rate seven decimals" to
                scenario(treatments = "[${treatmentJson(""""rateBasis":"FIXED","rate":"9.1234567"""")}]"),
            "invalid written agreement unknown" to
                scenario(
                    treatments = "[${treatmentJson(""""rateBasis":"FIXED","rate":"0","writtenAgreement":"MAYBE"""")}]",
                ),
            "invalid approved under repeated" to
                scenario(
                    treatments =
                        "[${treatmentJson(
                            """"rateBasis":"FIXED","rate":"0","counterpartyApprovedUnder":["MES","MES"]""",
                        )}]",
                ),
            "invalid approved under empty" to
                scenario(
                    treatments = "[${treatmentJson(
                        """"rateBasis":"FIXED","rate":"0","counterpartyApprovedUnder":[]""",
                    )}]",
                ),
            "invalid category code namespaced" to
                scenario(
                    treatments = "[${treatmentJson(
                        """"rateBasis":"FIXED","rate":"0"""",
                    ).replace("\"NA\"", "\"x:NA\"")}]",
                ),
            "invalid statement empty" to
                scenario(
                    treatments = "[${treatmentJson(
                        "\"rateBasis\":\"FIXED\",\"rate\":\"0\",\"ibt022Statement\":\"\"",
                    )}]",
                ),
        )

    private fun agree(
        samples: Map<String, String>,
        schema: String,
        decode: (ByteArray) -> Any,
    ) = samples.forEach { (name, json) ->
        val byName = name.startsWith("valid")
        check(
            runCatching {
                tools.jackson.databind.json
                    .JsonMapper()
                    .readTree(json)
            }.isSuccess,
        ) { "sample '$name' is not JSON: $json" }
        schemaAccepts(schema, json) shouldBe byName // the sample is what its name says, judged by the schema itself
        decodes(decode, json) shouldBe byName // and the decoder agrees
    }

    @Test
    fun `mapping contents decode as the mapping schema judges them`() =
        agree(mappingSamples, "mapping-pack-contents.schema.json", MappingPackContents::decode)

    @Test
    fun `code catalogue contents decode as the catalogue schema judges them`() =
        agree(catalogueSamples, "code-catalogue-pack-contents.schema.json", CodeCataloguePackContents::decode)

    @Test
    fun `scenario contents decode as the scenario schema judges them`() =
        agree(scenarioSamples, "scenario-pack-handlers.schema.json", ScenarioPackContents::decode)

    @Test
    fun `a failing decode names the field`() {
        shouldThrow<PackContentsFailure> { MappingPackContents.decode("{}".toByteArray()) }.field shouldBe "$.entries"
        shouldThrow<PackContentsFailure> {
            MappingPackContents.decode(mapping(entry(iras = "gst:SR")).toByteArray())
        }.field shouldBe "$.entries[0].irasCode"
        shouldThrow<PackContentsFailure> {
            MappingPackContents.decode(mapping(entry(code = "A".repeat(41))).toByteArray())
        }.field shouldBe "$.entries[0].internalTaxCode"
        shouldThrow<PackContentsFailure> { MappingPackContents.decode("[]".toByteArray()) }.message shouldContain
            "object"
        shouldThrow<PackContentsFailure> { CodeCataloguePackContents.decode("{".toByteArray()) }.field shouldBe "$"
    }

    @Test
    fun `one internal code or entry id twice is a failure`() {
        shouldThrow<PackContentsFailure> {
            MappingPackContents.decode(mapping(entry("a", "X"), entry("b", "X")).toByteArray())
        }.message shouldContain "internalTaxCode 'X' twice"
        shouldThrow<PackContentsFailure> {
            MappingPackContents.decode(mapping(entry("a", "X"), entry("a", "Y")).toByteArray())
        }.message shouldContain "entryId 'a' twice"
        shouldThrow<PackContentsFailure> {
            CodeCataloguePackContents.decode(catalogue(catalogueCode(), catalogueCode()).toByteArray())
        }.message shouldContain "code 'SR' twice"
    }

    @Test
    fun `decoded values are the pack's own`() {
        MappingPackContents
            .decode(mapping(entry(extra = ""","schemeTag":"SELF_BILLING"""")).toByteArray())
            .shouldContainExactly(MappingEntry("SR-STD", "SR", "SELF_BILLING"))
        val catalogue =
            CodeCataloguePackContents.decode(
                catalogue(catalogueCode("SRCA-C"), complete = "false").toByteArray(),
            )
        catalogue.complete shouldBe false
        catalogue.contains("SRCA-C") shouldBe true
        val scenario = ScenarioPackContents.decode(scenario().toByteArray())
        scenario.customerCumIssuer.direction shouldBe Direction.PURCHASE
        scenario.customerCumIssuer.submissionType shouldBe SubmissionType.TYPE_3
        scenario.supplierCumRecipient.submissionType shouldBe SubmissionType.TYPE_2
        scenario.treatments.single().rate shouldBe "0"
    }

    private fun versionOf(
        type: PackType,
        label: String,
    ) = PackVersion(
        PackManifest(PackId("$label-id"), type, PackVersionLabel(label), LocalDate.of(2026, 1, 1), null, "1.0.0"),
        null,
    )

    private fun artefact(contents: String) =
        PackArtefact("{}".toByteArray(), "{}".toByteArray(), ByteArray(0), contents.toByteArray())

    @Test
    fun `the reader resolves by the date it is given and decodes that version's contents`() {
        val dates = mutableListOf<LocalDate>()
        val reader =
            PackContentsReader(
                lookup = { type, _, date ->
                    dates += date
                    if (date < LocalDate.of(2026, 7, 1)) versionOf(type, "v1") else versionOf(type, "v2")
                },
                artefacts = { v ->
                    artefact(mapping(entry(iras = if (v.version.value == "v1") "SR" else "ZR")))
                },
            )
        val before = TransactionDate(LocalDate.of(2026, 6, 30))
        val onDate = TransactionDate(LocalDate.of(2026, 7, 1))
        reader.mappingAt(null, before)!!.single().irasCode shouldBe "SR"
        reader.mappingAt(null, onDate)!!.single().irasCode shouldBe "ZR"
        reader.versionAt(PackTypes.MAPPING, null, before)!!.version.value shouldBe "v1"
        dates.toSet() shouldBe setOf(before.value, onDate.value) // only the caller's dates, never the clock's
    }

    @Test
    fun `no version in force gives null and broken contents fail the read`() {
        PackContentsReader(
            { _, _, _ -> null },
            { null },
        ).scenarioAt(null, TransactionDate(LocalDate.of(2026, 1, 1))) shouldBe
            null
        val broken = PackContentsReader({ t, _, _ -> versionOf(t, "v1") }, { artefact("""{"entries":[]}""") })
        shouldThrow<PackContentsFailure> { broken.mappingAt(null, TransactionDate(LocalDate.of(2026, 1, 1))) }
        val unheld = PackContentsReader({ t, _, _ -> versionOf(t, "v1") }, { null })
        shouldThrow<IllegalStateException> { unheld.mappingAt(null, TransactionDate(LocalDate.of(2026, 1, 1))) }
    }
}
