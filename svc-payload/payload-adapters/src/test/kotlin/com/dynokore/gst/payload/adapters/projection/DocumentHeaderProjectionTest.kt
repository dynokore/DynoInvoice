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
import java.time.LocalDate
import kotlin.reflect.full.primaryConstructor

/** INV-T-18187: IBT-001 from the document number and IBT-002 from the document date (INV-REQ-714, INV-REQ-463). */
class DocumentHeaderProjectionTest {
    private val registry = DocumentBuilderRegistry.standard()
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val party =
        PartyIdentifierInput(PartyInput("SG_UEN", "201888888A"), PartyInput("0195", "SSC-CENTRE-9"), triple)
    private val date = LocalDate.of(2026, 11, 3)
    private val codes = listOf("380", "381")

    private fun build(
        code: String,
        number: String?,
        issued: LocalDate? = date,
        input: PartyIdentifierInput = party,
    ): BuildResult =
        (registry.find(BuilderKey(SpecificationFamily.PINT_SG, code)) as BuilderLookup.Found)
            .builder
            .build(
                input,
                DocumentHeaderInput(number, issued),
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
            )

    private fun texts(
        xml: String,
        path: String,
        code: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private fun idTexts(
        code: String,
        number: String,
    ) = texts((build(code, number) as BuildResult.Built).xml, "/Invoice/cbc:ID", code)

    @Test
    fun `INV-AC-766 the issued number is written to IBT-001 for 380 and 381`() {
        for (code in codes) idTexts(code, "INV-2026-0042") shouldBe listOf("INV-2026-0042")
    }

    @Test
    fun `INV-AC-766 a number with spaces is written verbatim`() {
        for (code in codes) idTexts(code, " A1 ") shouldBe listOf(" A1 ")
    }

    @Test
    fun `INV-AC-766 a 100 character number is written unchanged`() {
        val number = "N".repeat(100)
        for (code in codes) idTexts(code, number) shouldBe listOf(number)
    }

    @Test
    fun `INV-AC-766 the header input has exactly the two parameters`() {
        DocumentHeaderInput::class.primaryConstructor!!.parameters.map { it.name } shouldBe
            listOf("documentNumber", "documentDate")
    }

    @Test
    fun `INV-AC-766 a null, an empty and a blank number are refused`() {
        for (code in codes) {
            for (number in listOf(null, "", "   ", "\t")) {
                build(code, number) shouldBe BuildResult.Refused(RefusalReason.DOCUMENT_NUMBER_ABSENT)
            }
        }
    }

    @Test
    fun `INV-AC-18186 the date is written to IBT-002 as yyyy-MM-dd for 380 and 381`() {
        for (code in codes) {
            val xml = (build(code, "INV-2026-0042") as BuildResult.Built).xml
            texts(xml, "/Invoice/cbc:IssueDate", code) shouldBe listOf("2026-11-03")
            val early = (build(code, "X", LocalDate.of(2026, 1, 5)) as BuildResult.Built).xml
            texts(early, "/Invoice/cbc:IssueDate", code) shouldBe listOf("2026-01-05")
        }
    }

    @Test
    fun `INV-AC-18186 a null date is refused`() {
        for (code in codes) build(code, "INV-1", null) shouldBe BuildResult.Refused(RefusalReason.DOCUMENT_DATE_ABSENT)
    }

    @Test
    fun `INV-AC-18186 the refusal order is identity triple, number, date`() {
        val noTriple = party.copy(identityTriple = null)
        for (code in codes) {
            build(code, null, date, noTriple) shouldBe BuildResult.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
            build(code, null, null, noTriple) shouldBe BuildResult.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
            build(code, null, null) shouldBe BuildResult.Refused(RefusalReason.DOCUMENT_NUMBER_ABSENT)
            build(code, "  ", null) shouldBe BuildResult.Refused(RefusalReason.DOCUMENT_NUMBER_ABSENT)
        }
    }
}
