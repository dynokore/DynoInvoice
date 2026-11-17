package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.CustomizationId
import com.dynokore.gst.payload.domain.CustomizationIdResult
import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.ProfileId
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-24266: the SG_PEPPOL_BIS builders of 380 and 381 (INV-REQ-508). */
class SgPeppolBisBuildersTest {
    private val input =
        PartyIdentifierInput(
            PartyInput("SG_UEN", "201888888A"),
            PartyInput("0195", "SSC-CENTRE-9"),
            IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8"),
        )
    private val header = DocumentHeaderInput("INV-2026-0042", java.time.LocalDate.of(2026, 11, 3))
    private val endpoint = EndpointInputs(true, MdePackOffNetworkPrefix("{}"))

    private fun build(
        builder: DocumentBuilder,
        family: SpecificationFamily,
    ): BuildResult = builder.build(input, header, Direction.SALE, family, SubmissionType.TYPE_2, endpoint)

    private fun texts(
        xml: String,
        path: String,
        code: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private fun assertBis(
        builder: DocumentBuilder,
        code: String,
        root: String,
    ) {
        builder.key shouldBe BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, code)
        val selected = CustomizationId.expected(SpecificationFamily.SG_PEPPOL_BIS, SubmissionType.TYPE_2)
        val customization = (selected as CustomizationIdResult.Selected).value
        val profile = ProfileId.expected(Direction.SALE, SpecificationFamily.SG_PEPPOL_BIS)
        val xml = (build(builder, SpecificationFamily.SG_PEPPOL_BIS) as BuildResult.Built).xml
        texts(xml, "/Invoice/cbc:CustomizationID", code) shouldBe listOf(customization)
        texts(xml, "/Invoice/cbc:ProfileID", code) shouldBe listOf(profile)
        TableConformanceGate.parse(xml).documentElement.localName shouldBe root
    }

    @Test
    fun `INV-AC-24265 the SG_PEPPOL_BIS 380 builder writes an Invoice with the BIS IBT-024 and IBT-023`() =
        assertBis(SgPeppolBisInvoiceBuilder(), "380", "Invoice")

    @Test
    fun `INV-AC-24265 the SG_PEPPOL_BIS 381 builder writes a CreditNote with the BIS IBT-024 and IBT-023`() =
        assertBis(SgPeppolBisCreditNoteBuilder(), "381", "CreditNote")

    @Test
    fun `INV-AC-24265 a SG_PEPPOL_BIS builder refuses the PINT_SG family argument`() {
        shouldThrow<IllegalArgumentException> { build(SgPeppolBisInvoiceBuilder(), SpecificationFamily.PINT_SG) }
        shouldThrow<IllegalArgumentException> { build(SgPeppolBisCreditNoteBuilder(), SpecificationFamily.PINT_SG) }
    }

    @Test
    fun `INV-AC-24265 a registry without the BIS keys answers NoBuilder, never the PINT_SG builder`() {
        val pintOnly = DocumentBuilderRegistry(listOf(PintSgInvoiceBuilder(), PintSgCreditNoteBuilder()))
        for (code in listOf("380", "381")) {
            val key = BuilderKey(SpecificationFamily.SG_PEPPOL_BIS, code)
            pintOnly.find(key) shouldBe BuilderLookup.NoBuilder(key)
        }
    }
}
