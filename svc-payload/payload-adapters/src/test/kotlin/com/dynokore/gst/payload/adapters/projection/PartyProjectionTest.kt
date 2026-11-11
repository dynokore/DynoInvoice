package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.EndpointIdResult
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import com.dynokore.gst.payload.domain.party.RefusalReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.w3c.dom.NodeList

/** INV-T-7178: the party identifiers in the built document (INV-REQ-485, INV-REQ-699). */
class PartyProjectionTest {
    private val registry = DocumentBuilderRegistry.standard()
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val centre = PartyInput("0195", "SSC-CENTRE-9")

    private fun build(
        code: String,
        buyer: PartyInput,
        supplier: PartyInput = centre,
        identity: IdentityTriple? = triple,
    ): BuildResult {
        val found = registry.find(BuilderKey(SpecificationFamily.PINT_SG, code)) as BuilderLookup.Found
        return found.builder.build(
            PartyIdentifierInput(buyer, supplier, identity),
            DocumentHeaderInput("INV-2026-0042", java.time.LocalDate.of(2026, 11, 3)),
            Direction.SALE,
            SpecificationFamily.PINT_SG,
            SubmissionType.TYPE_2,
            EndpointInputs(true, MdePackOffNetworkPrefix("{}")),
        )
    }

    private fun nodes(
        xml: String,
        path: String,
        code: String,
    ): NodeList = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)

    private fun texts(xml: String): List<String> {
        val all = nodes(xml, "//*[not(*)] | //@*", "380")
        return (0 until all.length).map { all.item(it).textContent }
    }

    private fun built(r: BuildResult) = r as BuildResult.Built

    @Test
    fun `INV-AC-1503 the customer UEN is in IBT-047 and not in IBT-046 for 380 and 381`() {
        for (code in listOf("380", "381")) {
            val xml = built(build(code, PartyInput("SG_UEN", "201888888A"))).xml
            val ibt047 =
                nodes(xml, "/Invoice/cac:AccountingCustomerParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID", code)
            ibt047.length shouldBe 1
            ibt047.item(0).textContent shouldBe "201888888A"
            nodes(
                xml,
                "/Invoice/cac:AccountingCustomerParty/cac:Party/cac:PartyIdentification/cbc:ID",
                code,
            ).length shouldBe
                0
            texts(xml).count { it == "201888888A" } shouldBe 1
        }
    }

    @Test
    fun `INV-AC-1503 another scheme, a case variant or a padded scheme gives no IBT-047 and no UEN text`() {
        for (scheme in listOf("sg_uen", "SG_UEN2", "SG_UEN ", "")) {
            for (code in listOf("380", "381")) {
                // INV-T-7193: a non-UEN buyer has no endpoint ID, so no XML is built and no UEN text can be written.
                build(code, PartyInput(scheme, "201888888A")) shouldBe
                    BuildResult.EndpointRefused(EndpointIdResult.NoUenToAppend)
            }
        }
    }

    @Test
    fun `INV-AC-742 INV-AC-18131 IBT-030 and IBT-031 come from the triple, never from the caller supplier`() {
        for (code in listOf("380", "381")) {
            val result = built(build(code, PartyInput("SG_UEN", "201888888A")))
            nodes(
                result.xml,
                "/Invoice/cac:AccountingSupplierParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID",
                code,
            ).item(0).textContent shouldBe
                "201912345A"
            nodes(
                result.xml,
                "/Invoice/cac:AccountingSupplierParty/cac:Party/cac:PartyTaxScheme/cbc:CompanyID",
                code,
            ).item(0).textContent shouldBe
                "M2-1234567-8"
            texts(result.xml).none { it.contains("SSC-CENTRE-9") } shouldBe true
            result.senderId shouldBe "0195:201912345A"
            val other = built(build(code, PartyInput("SG_UEN", "201888888A"), supplier = PartyInput("0088", "OTHER-1")))
            other.xml shouldBe result.xml
        }
    }

    @Test
    fun `INV-AC-18131 IdentityTripleAbsent refuses for 380 and 381 whatever the caller supplier holds`() {
        for (code in listOf("380", "381")) {
            build(code, PartyInput("SG_UEN", "201888888A"), identity = null) shouldBe
                BuildResult.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
        }
        // Built is the only member that carries XML
        BuildResult::class.sealedSubclasses.map { it.simpleName }.toSet() shouldBe
            setOf("Built", "Refused", "CustomizationIdUnpublished", "EndpointRefused")
        BuildResult.Refused::class.java.declaredFields.map { it.name } shouldBe listOf("reason")
    }

    @Test
    fun `registry refuses key PINT_SG 383 with NoBuilder and holds exactly 380 and 381`() {
        val key = BuilderKey(SpecificationFamily.PINT_SG, "383")
        registry.find(key) shouldBe BuilderLookup.NoBuilder(key)
        registry.find(BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, "380")) shouldBe
            BuilderLookup.NoBuilder(BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, "380"))
        registry.keys() shouldBe
            setOf(BuilderKey(SpecificationFamily.PINT_SG, "380"), BuilderKey(SpecificationFamily.PINT_SG, "381"))
    }
}
