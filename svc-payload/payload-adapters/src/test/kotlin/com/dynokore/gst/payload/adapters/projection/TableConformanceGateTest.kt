package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.Direction
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.header.DocumentHeaderInput
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** INV-T-7178: the table conformance gate over the fixture pack and the probes that must make it fail. */
class TableConformanceGateTest {
    private val fixture: JsonNode =
        JsonMapper().readTree(
            javaClass.getResourceAsStream("/mde/mde-pack-fixture.json") ?: error("mde-pack-fixture.json is missing"),
        )
    private val spec = "PINT-SG-1.0"
    private val registry = DocumentBuilderRegistry.standard()
    private val partyTerms =
        setOf("IBT-001", "IBT-002", "IBT-023", "IBT-024", "IBT-030", "IBT-031", "IBT-047", "IBT-049")
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
                DocumentHeaderInput("INV-2026-0042", java.time.LocalDate.of(2026, 11, 3)),
                Direction.SALE,
                SpecificationFamily.PINT_SG,
                SubmissionType.TYPE_2,
                EndpointInputs(true, MdePackOffNetworkPrefix("{}")),
            ) as BuildResult.Built
        ).xml

    private fun rows(code: String) = TableConformanceGate.applyingRows(fixture, code, spec)

    private fun failures(
        code: String,
        rows: List<GateRow> = rows(code),
        xml: String = xml(code),
    ) = TableConformanceGate.check(xml, code, rows)

    private fun row(id: String) = rows("380").first { it.businessTermId == id }

    private fun starts(
        code: String,
        rule: String,
        vararg rows: GateRow,
    ) = failures(code, rows(code) + rows).any { it.startsWith("($rule)") }

    @Test
    fun `INV-AC-1503 INV-AC-18186 ConformanceGate passes for 380 and 381 over all applying rows`() {
        for (code in listOf("380", "381")) {
            rows(code).map { it.businessTermId }.toSet() shouldBe partyTerms
            failures(code) shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-18186 ConformanceGate rule a fails naming IBT-001 when cbc ID is removed`() {
        for (code in listOf("380", "381")) {
            val cut = xml(code).replace(Regex("<cbc:ID>[^<]*</cbc:ID>"), "").replace(Regex("<ID>[^<]*</ID>"), "")
            cut shouldNotBe xml(code)
            failures(code, xml = cut).any { it.startsWith("(a)") && "IBT-001" in it } shouldBe true
        }
    }

    @Test
    fun `INV-AC-18186 ConformanceGate rule a fails naming IBT-002 when cbc IssueDate is removed`() {
        for (code in listOf("380", "381")) {
            val cut = xml(code).replace(Regex("<[A-Za-z0-9]*:?IssueDate>[^<]*</[A-Za-z0-9]*:?IssueDate>"), "")
            cut shouldNotBe xml(code)
            failures(code, xml = cut).any { it.startsWith("(a)") && "IBT-002" in it } shouldBe true
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
            rows("380").map {
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
            rows("380").filter { it.businessTermId != "IBT-031" },
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
