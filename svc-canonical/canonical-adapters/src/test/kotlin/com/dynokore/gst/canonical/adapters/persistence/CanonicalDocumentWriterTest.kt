package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.application.document.ContentAddress
import com.dynokore.gst.canonical.application.document.ContentLine
import com.dynokore.gst.canonical.application.document.ContentParty
import com.dynokore.gst.canonical.application.document.ContentTotals
import com.dynokore.gst.canonical.application.document.DocumentContent
import com.dynokore.gst.canonical.application.document.LineTaxFact
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

class CanonicalDocumentWriterTest {
    private val writer = CanonicalDocumentWriter()
    private val mapper = JsonMapper()
    private val sr = LineTaxFact("SR", "9.00")
    private val lineMembers =
        listOf("lineNumber", "description", "quantity", "unitPrice", "lineAmount", "taxCode", "taxAmount")

    private fun line(
        n: Int,
        amount: String,
        unitCode: String? = null,
    ) = ContentLine(n, "item $n", BigDecimal("2"), "50.00", amount, unitCode, null)

    private fun content(
        totals: ContentTotals? = null,
        lines: List<ContentLine> = listOf(line(1, "100.00"), line(2, "200.00")),
        supplier: ContentParty? = null,
        buyer: ContentParty? = null,
    ) = DocumentContent("SGD", supplier, buyer, lines, totals)

    private fun render(c: DocumentContent): JsonNode = mapper.readTree(writer.write(c, "INVOICE", c.lines.map { sr }))

    @Test
    fun `INV-AC-21599 T8 declared totals are copied verbatim and never recomputed`() {
        val declared = ContentTotals("300.00", "27.10", "327.10", "300.00", "27.10", "327.10")
        val totals = render(content(declared))["totals"]["value"]
        totals["totalTax"].asString() shouldBe "27.10"
        totals["totalExcludingTax"].asString() shouldBe "300.00"
        totals["totalIncludingTax"].asString() shouldBe "327.10"
        totals["sgdTotalTax"].asString() shouldBe "27.10"
    }

    @Test
    fun `INV-AC-21599 T8 absent totals are derived by BR-CO-13 14 and 15`() {
        val totals = render(content())["totals"]["value"]
        totals["totalExcludingTax"].asString() shouldBe "300.00"
        totals["totalTax"].asString() shouldBe "27.00"
        totals["totalIncludingTax"].asString() shouldBe "327.00"
        totals.has("sgdTotalTax") shouldBe false
    }

    @Test
    fun `INV-AC-21599 T9a every required member is present and absent parties are empty objects`() {
        val doc = render(content())
        assertNoNullOrEmpty(doc)
        listOf("documentTypeCode", "currency", "parties", "lines", "taxSubtotals", "totals").forEach {
            doc.has(it) shouldBe
                true
        }
        doc["documentTypeCode"]["classification"].asString() shouldBe "NONE"
        doc["parties"]["classification"].asString() shouldBe "IDENTIFYING"
        doc["parties"]["value"]["supplier"] shouldBe mapper.readTree("{}")
        doc["parties"]["value"]["buyer"] shouldBe mapper.readTree("{}")
        doc["lines"]["value"].forEach { l -> lineMembers.forEach { l.has(it) shouldBe true } }
        doc["lines"]["value"][0]["taxAmount"].asString() shouldBe "9.00"
        doc["lines"]["value"][0]["quantity"].isNumber shouldBe true
        doc["taxSubtotals"]["value"].size() shouldBe 1
        doc["taxSubtotals"]["value"][0]["taxableAmount"].asString() shouldBe "300.00"
    }

    @Test
    fun `INV-AC-21599 T9b optional members that are absent are omitted, never null or empty`() {
        val bare = ContentParty(null, null, null, null, null)
        val partial = ContentParty("SG_UEN", "201234567A", null, null, ContentAddress(null, "049315", null))
        val doc = render(content(supplier = bare, buyer = partial))
        doc["parties"]["value"]["supplier"] shouldBe mapper.readTree("{}")
        val buyer = doc["parties"]["value"]["buyer"]
        buyer
            .propertyNames()
            .asSequence()
            .toList()
            .sorted() shouldBe
            listOf("address", "identifierScheme", "identifierValue")
        buyer["address"].propertyNames().toList() shouldBe listOf("postalCode")
        doc["lines"]["value"][0].has("unitCode") shouldBe false
        assertNoNullOrEmpty(doc)
        render(content(lines = listOf(line(1, "100.00", "EA"))))["lines"]["value"][0]["unitCode"].asString() shouldBe
            "EA"
    }

    @Test
    fun `INV-AC-21599 T9c a line without description quantity or unitPrice is refused`() {
        val bad = ContentLine(2, null, null, null, "200.00", null, null)
        val ex =
            assertThrows<IncompleteCanonicalDocument> {
                writer.write(content(lines = listOf(line(1, "100.00"), bad)), "INVOICE", listOf(sr, sr))
            }
        ex.lineNumbers shouldBe listOf(2)
    }

    private fun assertNoNullOrEmpty(n: JsonNode) {
        (n.isNull) shouldBe false
        if (n.isString) n.asString().isEmpty() shouldBe false
        n.forEach { assertNoNullOrEmpty(it) }
    }

    private fun supplyOf(
        amounts: List<String>,
        types: List<String?>,
    ): JsonNode {
        val c = content(lines = amounts.mapIndexed { i, a -> line(i + 1, a) })
        return mapper.readTree(writer.write(c, "INVOICE", types.map { LineTaxFact("SR", "9.00", it) }))
    }

    @Test
    fun `INV-AC-24847 one supply type is the value of the document with classification NONE`() {
        val doc = supplyOf(listOf("100.00", "50.00"), listOf("ZERO_RATED", "ZERO_RATED"))
        doc["supplyType"]["value"].asString() shouldBe "ZERO_RATED"
        doc["supplyType"]["classification"].asString() shouldBe "NONE"
        assertNoNullOrEmpty(doc)
    }

    private fun winner(
        amounts: List<String>,
        types: List<String>,
    ): String = supplyOf(amounts, types)["supplyType"]["value"].asString()

    @Test
    fun `INV-AC-24847 the group with the larger absolute amount wins and a credit note counts by absolute value`() {
        winner(listOf("100.00", "300.00"), listOf("STANDARD_RATED", "EXEMPT")) shouldBe "EXEMPT"
        winner(listOf("100.00", "-300.00"), listOf("STANDARD_RATED", "EXEMPT")) shouldBe "EXEMPT"
        winner(listOf("200.00", "-50.00", "-60.00"), listOf("STANDARD_RATED", "EXEMPT", "EXEMPT")) shouldBe
            "STANDARD_RATED"
    }

    @Test
    fun `INV-AC-24847 equal amounts go to the first in the fixed order`() {
        winner(listOf("100.00", "-100.00"), listOf("EXEMPT", "ZERO_RATED")) shouldBe "ZERO_RATED"
        winner(listOf("5.00", "5.00", "5.00"), listOf("IMPORTS", "OUT_OF_SCOPE", "STANDARD_RATED")) shouldBe
            "STANDARD_RATED"
    }

    @Test
    fun `INV-AC-24847 a line without an answer member gives no member and so do no lines`() {
        supplyOf(listOf("100.00", "50.00"), listOf("ZERO_RATED", null)).has("supplyType") shouldBe false
        supplyOf(listOf("100.00", "50.00"), listOf("ZERO_RATED", "")).has("supplyType") shouldBe false
        supplyOf(listOf("100.00"), listOf("")).has("supplyType") shouldBe false
        val none = content(lines = emptyList())
        mapper.readTree(writer.write(none, "INVOICE", emptyList())).has("supplyType") shouldBe false
    }

    @Test
    fun `INV-AC-24847 out-of-set supplyType is stored verbatim`() {
        winner(listOf("100.00", "40.00"), listOf("FUTURE_SUPPLY_TYPE", "FUTURE_SUPPLY_TYPE")) shouldBe
            "FUTURE_SUPPLY_TYPE"
    }

    @Test
    fun `INV-AC-24847 the look-alike zero_rated is carried unchanged`() {
        winner(listOf("100.00"), listOf("zero_rated")) shouldBe "zero_rated"
    }
}
