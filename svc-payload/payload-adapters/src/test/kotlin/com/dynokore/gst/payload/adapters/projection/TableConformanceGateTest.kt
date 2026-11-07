package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.party.IdentityTriple
import com.dynokore.gst.payload.domain.party.PartyIdentifierInput
import com.dynokore.gst.payload.domain.party.PartyInput
import io.kotest.matchers.shouldBe
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
            ).builder.build(input) as BuildResult.Built
        ).xml

    private fun rows(code: String) = TableConformanceGate.applyingRows(fixture, code, spec)

    private fun failures(
        code: String,
        rows: List<GateRow> = rows(code),
        xml: String = xml(code),
    ) = TableConformanceGate.check(xml, rows)

    private val ibt047 = "/Invoice/cac:AccountingCustomerParty/cac:Party/cac:PartyLegalEntity/cbc:CompanyID"

    @Test
    fun `INV-AC-1503 ConformanceGate passes for 380 and 381 over the fixture rows`() {
        rows("380").size shouldBe 3
        rows("381").size shouldBe 3
        failures("380") shouldBe emptyList()
        failures("381") shouldBe emptyList()
    }

    @Test
    fun `ConformanceGate fails on a UEN written at IBT-046 instead of IBT-047`() {
        val wrong =
            xml("380")
                .replace("PartyLegalEntity", "PartyIdentification", ignoreCase = false)
        // both supplier and buyer legal entities moved; the leaf at IBT-046 is selected by no row
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
        val rows = rows("380").filter { it.businessTermId != "IBT-031" }
        failures("380", rows).any { it.startsWith("(b)") && "selected by 0 rows" in it } shouldBe true
    }

    @Test
    fun `ConformanceGate rule b fails when a leaf is selected by two rows`() {
        val rows = rows("380")
        val twice = rows + rows.first { it.businessTermId == "IBT-030" }
        failures("380", twice).any { it.startsWith("(b)") && "selected by 2 rows" in it } shouldBe true
    }

    @Test
    fun `ConformanceGate rule c fails on a CreditNote path in a row of type 380`() {
        val rows = rows("380") + GateRow("IBT-047", ibt047.replace("/Invoice/", "/CreditNote/"))
        failures("380", rows).any { it.startsWith("(c)") } shouldBe true
    }

    @Test
    fun `ConformanceGate rule d fails when no row applies`() {
        failures("380", emptyList()).any { it.startsWith("(d)") } shouldBe true
        TableConformanceGate.applyingRows(fixture, "383", spec) shouldBe emptyList()
        TableConformanceGate.applyingRows(fixture, "380", "PINT-SG-1.0 ") shouldBe emptyList()
    }

    @Test
    fun `ConformanceGate applies only rows with the document type code by equality`() {
        rows("380").all { it.syntaxPath.startsWith("/Invoice/") } shouldBe true
        rows("381").all { it.syntaxPath.startsWith("/CreditNote/") } shouldBe true
    }
}
