package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import com.dynokore.gst.payload.domain.party.RefusalReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** INV-T-7184: IBT-023 and IBT-024 in the built document, and the refusal of unpublished pairs (INV-REQ-508). */
class CustomizationProfileProjectionTest {
    private val fixture: JsonNode =
        JsonMapper().readTree(
            javaClass.getResourceAsStream("/mde/mde-pack-fixture.json") ?: error("mde-pack-fixture.json is missing"),
        )
    private val registry = DocumentBuilderRegistry.standard()
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val input =
        PartyIdentifierInput(PartyInput("SG_UEN", "201888888A"), PartyInput("0195", "SSC-CENTRE-9"), triple)
    private val header = DocumentHeaderInput("INV-2026-0042", java.time.LocalDate.of(2026, 11, 3))
    private val bisType2 =
        "urn:cen.eu:en16931:2017#conformant#urn:fdc:peppol.eu:2017:poacc:billing:LocalTaxInvoice:sg:1.0"

    private fun build(
        code: String,
        direction: Direction,
        family: SpecificationFamily,
        type: SubmissionType,
        party: PartyIdentifierInput = input,
    ): BuildResult =
        (registry.find(BuilderKey(SpecificationFamily.PINT_SG, code)) as BuilderLookup.Found)
            .builder
            .build(party, header, direction, family, type, EndpointInputs(true, MdePackOffNetworkPrefix("{}")))

    private fun texts(
        xml: String,
        path: String,
        code: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    // The gate over all applying rows.
    private fun gate(
        xml: String,
        code: String,
        rows: List<GateRow> = applying(code),
    ) = TableConformanceGate.check(xml, code, rows)

    private fun applying(code: String) = TableConformanceGate.applyingRows(fixture, code, "PINT-SG-1.0")

    private fun assertIds(
        family: SpecificationFamily,
        direction: Direction,
        type: SubmissionType,
        customization: String,
        profile: String,
    ) {
        for ((code, root) in listOf("380" to "Invoice", "381" to "CreditNote")) {
            val xml = (build(code, direction, family, type) as BuildResult.Built).xml
            texts(xml, "/$root/cbc:CustomizationID", code) shouldBe listOf(customization)
            texts(xml, "/$root/cbc:ProfileID", code) shouldBe listOf(profile)
            gate(xml, code) shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-1505 a Type 2 SG Peppol BIS sale carries the BIS LocalTaxInvoice IBT-024 and the billing IBT-023`() =
        assertIds(
            SpecificationFamily.SG_PEPPOL_BIS,
            Direction.SALE,
            SubmissionType.TYPE_2,
            bisType2,
            "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
        )

    @Test
    fun `INV-AC-1505 a Type 2 PINT_SG sale carries the PINT LocalTaxInvoice IBT-024 and urn peppol bis billing`() =
        assertIds(
            SpecificationFamily.PINT_SG,
            Direction.SALE,
            SubmissionType.TYPE_2,
            "urn:peppol:pint:billing-1@sg-1:LocalTaxInvoice:sg:1.0",
            "urn:peppol:bis:billing",
        )

    @Test
    fun `INV-AC-1505 a PURCHASE with SG_PEPPOL_BIS carries the Payables IBT-023`() =
        assertIds(
            SpecificationFamily.SG_PEPPOL_BIS,
            Direction.PURCHASE,
            SubmissionType.TYPE_2,
            bisType2,
            "urn:fdc:peppol.eu:2017:poacc:Payables:01:1.0",
        )

    @Test
    fun `CustomizationIdUnpublished for TYPE_1B and TYPE_3 with each family, and the result holds no XML`() {
        for (family in SpecificationFamily.entries) {
            for (type in listOf(SubmissionType.TYPE_1B, SubmissionType.TYPE_3)) {
                for (code in listOf("380", "381")) {
                    val result = build(code, Direction.SALE, family, type)
                    result shouldBe BuildResult.CustomizationIdUnpublished(family, type)
                }
            }
        }
        BuildResult.CustomizationIdUnpublished::class.java.declaredFields
            .map { it.name }
            .sorted() shouldBe listOf("family", "submissionType")
    }

    @Test
    fun `CustomizationIdUnpublished comes second, the identity triple refusal wins`() {
        val noTriple = PartyIdentifierInput(input.buyer, input.callerSupplier, null)
        for (code in listOf("380", "381")) {
            build(code, Direction.SALE, SpecificationFamily.PINT_SG, SubmissionType.TYPE_3, noTriple) shouldBe
                BuildResult.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
        }
    }

    private fun built380() =
        (
            build(
                "380",
                Direction.SALE,
                SpecificationFamily.SG_PEPPOL_BIS,
                SubmissionType.TYPE_2,
            ) as BuildResult.Built
        ).xml

    private fun row(id: String) = applying("380").first { it.businessTermId == id }

    @Test
    fun `ConformanceGate rule b fails when the IBT-024 row is removed`() {
        gate(built380(), "380", applying("380").filter { it.businessTermId != "IBT-024" })
            .any { "selected by 0 rows" in it && "CustomizationID" in it } shouldBe true
    }

    @Test
    fun `ConformanceGate rule a fails on the look-alike path CustomizationID2`() {
        val rows =
            applying("380").map {
                if (it.businessTermId == "IBT-024") it.copy(syntaxPath = "/Invoice/cbc:CustomizationID2") else it
            }
        gate(built380(), "380", rows).any { it.startsWith("(a)") && "IBT-024" in it } shouldBe true
    }

    @Test
    fun `ConformanceGate rule c fails on a CreditNote path in the IBT-024 row of 380 and 381`() {
        val rows =
            applying("380").filter { it.businessTermId != "IBT-024" } +
                row("IBT-024").copy(syntaxPath = "/CreditNote/cbc:CustomizationID")
        gate(built380(), "380", rows).any { it.startsWith("(c)") } shouldBe true
    }

    @Test
    fun `the fixture holds one row each of IBT-023 and IBT-024 for 380 and 381 with the Invoice path`() {
        for ((id, leaf) in listOf("IBT-023" to "ProfileID", "IBT-024" to "CustomizationID")) {
            for (code in listOf("380", "381")) {
                val rows = applying(code).filter { it.businessTermId == id }
                rows.map { it.syntaxPath } shouldBe listOf("/Invoice/cbc:$leaf")
                rows.map { it.documentTypeCodes } shouldBe listOf(listOf("380", "381"))
            }
        }
    }
}
