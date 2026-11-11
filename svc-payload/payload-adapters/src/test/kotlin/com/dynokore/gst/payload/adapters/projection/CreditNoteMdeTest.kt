package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.MdeCompleteness
import com.dynokore.gst.payload.domain.MdeResult
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** INV-T-7187: the credit-note MDE set against the completeness check (INV-REQ-695). */
class CreditNoteMdeTest {
    private val registry = DocumentBuilderRegistry.standard()
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val input =
        PartyIdentifierInput(PartyInput("SG_UEN", "201888888A"), PartyInput("0195", "SSC-CENTRE-9"), triple)
    private val rows = FixtureValueMap.mdeRows()

    private fun build(
        code: String,
        note: String?,
    ): String {
        val found = registry.find(BuilderKey(SpecificationFamily.PINT_SG, code)) as BuilderLookup.Found
        val result =
            found.builder.build(
                input,
                DocumentHeaderInput("INV-2026-0042", LocalDate.of(2026, 11, 3), note),
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
                EndpointInputs(true, MdePackOffNetworkPrefix("{}")),
            )
        return (result as BuildResult.Built).xml
    }

    private fun check(
        xml: String,
        code: String,
    ): MdeResult =
        MdeCompleteness.check(
            rows,
            code,
            SubmissionType.TYPE_1A,
            emptySet(),
            FixtureValueMap.read(xml, code, FixtureValueMap.gateRows(code)),
        )

    private fun noteTexts(
        xml: String,
        path: String,
        code: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, code)
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    @Test
    fun `INV-AC-20083 without a note the 381 document is missing IBT-022 and the 380 document is complete`() {
        check(build("381", null), "381") shouldBe MdeResult.Missing(listOf("IBT-022"))
        check(build("380", null), "380") shouldBe MdeResult.Complete
    }

    @Test
    fun `INV-AC-20083 with the note Thanks the 381 document is complete and both documents hold one Note node`() {
        val credit = build("381", "Thanks")
        check(credit, "381") shouldBe MdeResult.Complete
        noteTexts(credit, "/CreditNote/cbc:Note", "381") shouldBe listOf("Thanks")
        val invoice = build("380", "Thanks")
        noteTexts(invoice, "/Invoice/cbc:Note", "380") shouldBe listOf("Thanks")
    }

    @Test
    fun `INV-AC-20083 a note of three spaces is kept unchanged and counts as missing`() {
        val credit = build("381", "   ")
        check(credit, "381") shouldBe MdeResult.Missing(listOf("IBT-022"))
        noteTexts(credit, "/CreditNote/cbc:Note", "381") shouldBe listOf("   ")
    }

    @Test
    fun `INV-AC-20083 the conformance gate passes with the note and names IBT-022 without it`() {
        TableConformanceGate.check(build("381", "Thanks"), "381", FixtureValueMap.gateRows("381")) shouldBe emptyList()
        val failures = TableConformanceGate.check(build("381", null), "381", FixtureValueMap.gateRows("381"))
        failures.isNotEmpty() shouldBe true
        failures.joinToString("\n") shouldContain "IBT-022"
    }
}
