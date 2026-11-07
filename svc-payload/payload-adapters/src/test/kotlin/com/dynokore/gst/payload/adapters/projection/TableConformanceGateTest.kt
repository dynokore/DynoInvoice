package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Fixture terms that apply to 380 and 381 but are not written by the builders here; INV-T-18187 removes this. */
private val UNWRITTEN_TERMS = listOf("IBT-001", "IBT-002")

/** INV-T-7178: the table conformance gate over the fixture pack and the probes that must make it fail. */
class TableConformanceGateTest {
    private val fixture: JsonNode =
        JsonMapper().readTree(
            javaClass.getResourceAsStream("/mde/mde-pack-fixture.json") ?: error("mde-pack-fixture.json is missing"),
        )
    private val spec = "PINT-SG-1.0"
    private val registry = DocumentBuilderRegistry.standard()
    private val partyTerms = setOf("IBT-023", "IBT-024", "IBT-030", "IBT-031", "IBT-047")
    private val input =
        PartyIdentifierInput(
            PartyInput("SG_UEN", "201888888A"),
            PartyInput("0195", "SSC-CENTRE-9"),
            IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8"),
        )

    private fun xml(code: String) =
        (
            (
                registry.find(
                    BuilderKey(SpecificationFamily.PINT_SG, code),
                ) as BuilderLookup.Found
            ).builder.build(
                input,
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
            ) as BuildResult.Built
        ).xml

    private fun rows(code: String) = TableConformanceGate.applyingRows(fixture, code, spec)

    // Applying rows without the UNWRITTEN_TERMS rows (coverage-gap ruling): the terms the builders write.
    private fun partyRows(code: String) = rows(code).filter { it.businessTermId !in UNWRITTEN_TERMS }

    private fun failures(
        code: String,
        rows: List<GateRow> = partyRows(code),
        xml: String = xml(code),
    ) = TableConformanceGate.check(xml, code, rows)

    private fun row(id: String) = partyRows("380").first { it.businessTermId == id }

    private fun starts(
        code: String,
        rule: String,
        vararg rows: GateRow,
    ) = failures(code, partyRows(code) + rows).any { it.startsWith("($rule)") }

    @Test
    fun `INV-AC-1503 ConformanceGate passes for 380 and 381 over the party rows`() {
        for (code in listOf("380", "381")) {
            rows(code).map { it.businessTermId }.filter { it !in UNWRITTEN_TERMS }.toSet() shouldBe partyTerms
            val applying = rows(code).map { it.businessTermId }.toSet()
            UNWRITTEN_TERMS.all { it in applying } shouldBe true
            failures(code) shouldBe emptyList()
        }
    }

    @Test
    fun `ConformanceGate rule a fails over all applying rows naming IBT-001 and IBT-002`() {
        UNWRITTEN_TERMS shouldBe listOf("IBT-001", "IBT-002")
        for (code in listOf("380", "381")) {
            val fails = failures(code, rows(code)).filter { it.startsWith("(a)") }
            UNWRITTEN_TERMS.forEach { t -> fails.any { t in it } shouldBe true }
        }
    }

    @Test
    fun `ConformanceGate rule b fails on a UEN written at IBT-046 instead of IBT-047`() {
        val wrong = xml("380").replace("PartyLegalEntity", "PartyIdentification")
        failures("380", xml = wrong).any { it.startsWith("(b)") } shouldBe true
    }

    @Test
    fun `ConformanceGate rule a fails on a look-alike path`() {
        val rows =
            partyRows("380").map {
                if (it.businessTermId ==
                    "IBT-047"
                ) {
                    it.copy(syntaxPath = "${it.syntaxPath}2")
                } else {
                    it
                }
            }
        failures("380", rows).any { it.startsWith("(a)") && "IBT-047" in it } shouldBe true
    }

    @Test
    fun `ConformanceGate rule b fails on a written leaf whose row is removed`() {
        failures(
            "380",
            partyRows("380").filter { it.businessTermId != "IBT-031" },
        ).any { "selected by 0 rows" in it } shouldBe
            true
    }

    @Test
    fun `ConformanceGate rule b fails when a leaf is selected by two rows under a look-alike id`() {
        starts("380", "b", row("IBT-030").copy(businessTermId = "IBT-030-1")) shouldBe true
    }

    @Test
    fun `ConformanceGate rule c fails on a wrong first step, each alone`() {
        val p = row("IBT-030").syntaxPath
        val creditNote = p.replaceFirst("/Invoice/", "/CreditNote/")
        starts("380", "c", row("IBT-030").copy(syntaxPath = creditNote, documentTypeCodes = listOf("380"))) shouldBe
            true
        starts("381", "c", row("IBT-030").copy(syntaxPath = p, documentTypeCodes = listOf("381"))) shouldBe true
        starts("380", "c", row("IBT-030").copy(syntaxPath = creditNote)) shouldBe true
        starts("381", "c", row("IBT-030").copy(syntaxPath = creditNote)) shouldBe true
        starts("380", "c", row("IBT-030").copy(syntaxPath = p.replaceFirst("/Invoice/", "/root:Invoice/"))) shouldBe
            true
        starts(
            "380",
            "c",
            row("IBT-030").copy(syntaxPath = p.replaceFirst("/Invoice/", "/*[local-name()='Invoice']/")),
        ) shouldBe
            true
        starts("380", "c", row("IBT-030").copy(syntaxPath = p.replaceFirst("/Invoice/", "/*/"))) shouldBe true
    }

    @Test
    fun `ConformanceGate rule d fails when no row applies`() {
        failures("380", emptyList()).any { it.startsWith("(d)") } shouldBe true
        TableConformanceGate.applyingRows(fixture, "383", spec) shouldBe emptyList()
        TableConformanceGate.applyingRows(fixture, "380", "PINT-SG-1.0 ") shouldBe emptyList()
    }

    @Test
    fun `ConformanceGate rule e fails on a root in no namespace, a look-alike namespace or the other root`() {
        val invoice = xml("380")
        val ns = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
        val noNs = invoice.replace(" xmlns:ns3=\"$ns\"", "").replace(Regex("<(/?)[A-Za-z0-9]+:Invoice"), "<$1Invoice")
        failures("380", xml = noNs.replace("xmlns=\"$ns\"", "")).any { it.startsWith("(e)") } shouldBe true
        failures("380", xml = invoice.replace("$ns", "$ns.1")).any { it.startsWith("(e)") } shouldBe true
        failures("381", xml = invoice).any { it.startsWith("(e)") } shouldBe true
    }

    @Test
    fun `ConformanceGate select rewrites only the first step`() {
        val doc = TableConformanceGate.parse(xml("381"))
        val p = row("IBT-030").syntaxPath
        TableConformanceGate.select(doc, p, "381").length shouldBe 1
        TableConformanceGate.select(doc, p, "380").length shouldBe 0
    }
}
