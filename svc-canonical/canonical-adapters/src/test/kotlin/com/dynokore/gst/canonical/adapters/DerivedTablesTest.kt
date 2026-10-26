package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.domain.document.CanonicalShapeException
import com.dynokore.gst.canonical.domain.document.SubtotalCoverageViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-2572: lines, subtotals and parties are derived from the canonical JSONB and rebuildable from it. */
class DerivedTablesTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    private class Line(
        val code: String,
        val amount: String,
        val tax: String,
    )

    private fun canonical(
        lines: List<Line>,
        subtotals: List<Line>,
        type: String = "380",
    ): String {
        val lineJson =
            lines.withIndex().joinToString(",") { (index, line) ->
                """{"lineNumber":${index + 1},"description":"item ${index + 1}","quantity":2.5,""" +
                    """"unitPrice":"${line.amount}.000000","lineAmount":"${line.amount}.00",""" +
                    """"taxCode":"${line.code}","taxAmount":"${line.tax}.00","extra":1}"""
            }
        val subtotalJson =
            subtotals.joinToString(",") {
                """{"taxCode":"${it.code}","taxableAmount":"${it.amount}.00","taxAmount":"${it.tax}.00"}"""
            }
        return """{"documentTypeCode":{"value":"$type","classification":"content"},""" +
            """"parties":{"value":{"supplier":{"name":"Supplier Pte Ltd","uen":"201912345A"},""" +
            """"buyer":{"name":"Buyer","address":{"city":"Singapore"}}},"classification":"content"},""" +
            """"lines":{"value":[$lineJson],"classification":"content"},""" +
            """"taxSubtotals":{"value":[$subtotalJson],"classification":"content"}}"""
    }

    private fun coherent(
        type: String = "380",
        sign: String = "",
    ): String {
        val standard = Line("SR", "${sign}100", "${sign}9")
        val zero = Line("ZR", "${sign}50", "${sign}0")
        return canonical(listOf(standard, zero), listOf(standard, zero), type)
    }

    private val tables = listOf("document_line", "document_subtotal", "document_party")

    private fun snapshot(): Map<String, List<List<String?>>> =
        tables.associateWith { table ->
            DocumentSql.rows("select t::text from canonical.$table t where tenant_id = ? order by 1", tenant)
        }

    private fun amend(
        doc: UUID,
        from: Int,
        canonical: String,
    ) = kit.asTenant(tenant) { kit.versions.createVersion(doc, from, DocumentKit.version(day, canonical)) }

    private fun versionCount(doc: UUID) =
        DocumentSql.one("select count(*)::text from canonical.document_version where doc_uuid = ?", doc)

    private fun documentCount(doc: UUID) =
        DocumentSql.one("select count(*)::text from canonical.document where doc_uuid = ?", doc)

    private fun create(canonical: String) {
        val document = DocumentKit.document(tenant, entity, day, canonical = canonical)
        kit.insert(document)
    }

    @Test
    fun `a line with a tax code that has no subtotal is rejected naming C-6 and nothing is written`() {
        val document = DocumentKit.document(tenant, entity, day, canonical = coherent())
        kit.insert(document)
        val before = snapshot()
        val uncovered = canonical(listOf(Line("SR", "10", "1"), Line("ZR", "5", "0")), listOf(Line("SR", "10", "1")))
        val failure = shouldThrow<SubtotalCoverageViolation> { amend(document.docUuid, 1, uncovered) }
        failure.message shouldContain "C-6"
        failure.message shouldContain "ZR"
        versionCount(document.docUuid) shouldBe "1"
        snapshot() shouldBe before
    }

    @Test
    fun `a subtotal with no line is rejected naming C-6 and the new document is not written`() {
        val orphan = canonical(listOf(Line("SR", "10", "1")), listOf(Line("SR", "10", "1"), Line("ZR", "5", "0")))
        val document = DocumentKit.document(tenant, entity, day, canonical = orphan)
        shouldThrow<SubtotalCoverageViolation> { kit.insert(document) }.message shouldContain "C-6"
        documentCount(document.docUuid) shouldBe "0"
        snapshot().values.forEach { it.shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-11133 a version with two tax codes that cover each other is written with its rows`() {
        create(coherent())
        DocumentSql.rows(
            "select line_number::text, description, quantity::text, unit_price::text, line_amount::text, " +
                "tax_code, tax_amount::text from canonical.document_line where tenant_id = ? order by 1",
            tenant,
        ) shouldBe
            listOf(
                listOf("1", "item 1", "2.500000", "100.000000", "100.00", "SR", "9.00"),
                listOf("2", "item 2", "2.500000", "50.000000", "50.00", "ZR", "0.00"),
            )
        DocumentSql.rows(
            "select tax_code, taxable_amount::text, tax_amount::text from canonical.document_subtotal " +
                "where tenant_id = ? order by 1",
            tenant,
        ) shouldBe listOf(listOf("SR", "100.00", "9.00"), listOf("ZR", "50.00", "0.00"))
        // jsonb does not keep key order, so each party is compared as a jsonb value
        DocumentSql.rows(
            "select role, (party = '{\"name\":\"Buyer\",\"address\":{\"city\":\"Singapore\"}}'::jsonb or " +
                "party = '{\"uen\":\"201912345A\",\"name\":\"Supplier Pte Ltd\"}'::jsonb)::text " +
                "from canonical.document_party where tenant_id = ? order by 1",
            tenant,
        ) shouldBe listOf(listOf("buyer", "true"), listOf("supplier", "true"))
    }

    private val wrapped = """"classification":"content"}"""
    private val noLines = """"lines":{"value":[],$wrapped"""
    private val noSubtotals = """"taxSubtotals":{"value":[],$wrapped"""
    private val bothParties = """"parties":{"value":{"supplier":{},"buyer":{}},$wrapped"""

    private fun counts() =
        listOf("document_version", "document_line", "document_subtotal", "document_party").map {
            DocumentSql.one("select count(*)::text from canonical.$it where tenant_id = ?", tenant)
        }

    private fun refused(canonical: String) {
        val before = counts()
        shouldThrow<CanonicalShapeException> { create(canonical) }
        counts() shouldBe before
    }

    private fun lineWith(quantity: String) =
        """{"lines":{"value":[{"lineNumber":1,"description":"d","quantity":$quantity,"unitPrice":"1.000000",""" +
            """"lineAmount":"1.00","taxCode":"SR","taxAmount":"0.00"}],$wrapped,""" +
            """$noSubtotals,$bothParties}"""

    private fun withSubtotal(document: String) =
        document.replace(
            noSubtotals,
            """"taxSubtotals":{"value":[{"taxCode":"SR","taxableAmount":"1.00","taxAmount":"0.00"}],$wrapped""",
        )

    @Test
    fun `a document without lines is refused and nothing is written`() = refused("{$noSubtotals,$bothParties}")

    @Test
    fun `a document without taxSubtotals is refused and nothing is written`() = refused("{$noLines,$bothParties}")

    @Test
    fun `INV-AC-11133 a document without parties is refused and nothing is written`() =
        refused("{$noLines,$noSubtotals}")

    @Test
    fun `parties without a buyer is refused and nothing is written`() =
        refused("""{$noLines,$noSubtotals,"parties":{"value":{"supplier":{}},$wrapped}""")

    @Test
    fun `parties without a supplier is refused and nothing is written`() =
        refused("""{$noLines,$noSubtotals,"parties":{"value":{"buyer":{}},$wrapped}""")

    @Test
    fun `a line without a quantity is refused and nothing is written`() =
        refused(
            """{"lines":{"value":[{"lineNumber":1,"description":"d","unitPrice":"1.000000","lineAmount":"1.00",""" +
                """"taxCode":"SR","taxAmount":"0.00"}],$wrapped,$noSubtotals,$bothParties}""",
        )

    @Test
    fun `a repeated lineNumber or subtotal taxCode and a non-object document are refused`() {
        val line = """{"lineNumber":1,"description":"d","quantity":1,"unitPrice":"1.000000","lineAmount":"1.00","""
        val twice = """$line"taxCode":"SR","taxAmount":"0.00"},$line"taxCode":"SR","taxAmount":"0.00"}"""
        refused("""{"lines":{"value":[$twice],$wrapped,$noSubtotals,$bothParties}""")
        val subtotal = """{"taxCode":"SR","taxableAmount":"1.00","taxAmount":"0.00"}"""
        refused("""{$noLines,"taxSubtotals":{"value":[$subtotal,$subtotal],$wrapped,$bothParties}""")
        refused("[]")
    }

    @Test
    fun `empty lines and subtotals with both parties give two party rows and an extra key is ignored`() {
        create("""{"x":1,$noLines,$noSubtotals,$bothParties}""")
        counts() shouldBe listOf("1", "0", "0", "2")
    }

    @Test
    fun `a quantity with six decimals and one with seventeen integer digits are stored and read back equal`() {
        create(withSubtotal(lineWith("1.234567")))
        create(withSubtotal(lineWith("12345678901234567.123456")))
        DocumentSql.rows(
            "select quantity::text from canonical.document_line where tenant_id = ? order by 1",
            tenant,
        ) shouldBe
            listOf(listOf("1.234567"), listOf("12345678901234567.123456"))
    }

    @Test
    fun `a quantity with seven decimals, eighteen integer digits or a string is refused`() {
        refused(withSubtotal(lineWith("1.2345678")))
        refused(withSubtotal(lineWith("100000000000000000")))
        refused(withSubtotal(lineWith("\"1\"")))
    }
}
