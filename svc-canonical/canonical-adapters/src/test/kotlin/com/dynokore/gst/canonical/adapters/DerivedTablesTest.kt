package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.domain.document.CanonicalShapeException
import com.dynokore.gst.canonical.domain.document.SubtotalCoverageViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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

    private fun canonicals() =
        DocumentSql.rows(
            "select doc_uuid::text, version::text, canonical::text from canonical.document_version " +
                "where tenant_id = ? order by 1, 2",
            tenant,
        )

    private fun amend(
        doc: UUID,
        from: Int,
        canonical: String,
    ) = kit.asTenant(tenant) { kit.versions.createVersion(doc, from, DocumentKit.version(day, canonical)) }

    private fun seed(): List<UUID> =
        listOf("380", "381", "380").mapIndexed { index, type ->
            val sign = if (type == "381") "-" else ""
            val document = DocumentKit.document(tenant, entity, day, canonical = coherent(type, sign))
            kit.insert(document)
            amend(document.docUuid, 1, coherent(type, sign).replace("item 1", "item 1 v2"))
            amend(document.docUuid, 2, coherent(type, sign).replace("Buyer", "Buyer $index"))
            document.docUuid
        }

    private fun versionCount(doc: UUID) =
        DocumentSql.one("select count(*)::text from canonical.document_version where doc_uuid = ?", doc)

    private fun documentCount(doc: UUID) =
        DocumentSql.one("select count(*)::text from canonical.document where doc_uuid = ?", doc)

    private fun create(canonical: String) {
        val document = DocumentKit.document(tenant, entity, day, canonical = canonical)
        kit.insert(document)
    }

    @Test
    fun `INV-AC-2573 derived rows deleted and rebuilt from the canonical JSONB equal the originals`() {
        seed()
        val before = snapshot()
        val jsonb = canonicals()
        before.getValue("document_line") shouldHaveSize 18
        before.getValue("document_subtotal") shouldHaveSize 18
        before.getValue("document_party") shouldHaveSize 18
        tables.forEach { DocumentSql.execute("delete from canonical.$it where tenant_id = '$tenant'") }
        snapshot().values.forEach { it.shouldBeEmpty() }
        val report = kit.asTenant(tenant) { kit.rebuilds.rebuildAllDerived(tenant) }
        report.rejected shouldBe emptyMap()
        report.rebuilt shouldHaveSize 9
        snapshot() shouldBe before
        canonicals() shouldBe jsonb
    }

    @Test
    fun `INV-AC-10347 rebuild restores the rows and a version violating C-6 writes nothing`() {
        val docs = seed()
        val before = snapshot()
        val jsonb = canonicals()
        tables.forEach { DocumentSql.execute("delete from canonical.$it where tenant_id = '$tenant'") }
        kit.asTenant(tenant) { kit.rebuilds.rebuildAllDerived(tenant) }.rejected shouldBe emptyMap()
        snapshot() shouldBe before
        canonicals() shouldBe jsonb
        val uncovered = canonical(listOf(Line("SR", "100", "9"), Line("ZR", "50", "0")), listOf(Line("SR", "100", "9")))
        val refused = shouldThrow<SubtotalCoverageViolation> { amend(docs[0], 3, uncovered) }
        refused.message shouldContain "C-6"
        versionCount(docs[0]) shouldBe "3"
        DocumentSql.one("select current_version::text from canonical.document where doc_uuid = ?", docs[0]) shouldBe "3"
        snapshot() shouldBe before
        canonicals() shouldBe jsonb
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
    fun `a version with two tax codes that cover each other is written with its rows`() {
        create(coherent())
        val rows = snapshot()
        rows.getValue("document_line") shouldHaveSize 2
        rows.getValue("document_subtotal") shouldHaveSize 2
        rows.getValue("document_party") shouldHaveSize 2
    }

    @Test
    fun `a rebuild of a stored version that violates C-6 reports C-6 and writes none of its rows`() {
        val document = DocumentKit.document(tenant, entity, day, canonical = coherent())
        kit.insert(document)
        val bad = canonical(listOf(Line("SR", "10", "1")), listOf(Line("SR", "10", "1"), Line("ZR", "5", "0")))
        DocumentSql.execute(
            "update canonical.document_version set canonical = '$bad'::jsonb where doc_uuid = '${document.docUuid}'",
        )
        val before = snapshot()
        val report = kit.asTenant(tenant) { kit.rebuilds.rebuildDerived(document.docUuid, 1) }
        report.rebuilt.shouldBeEmpty()
        report.rejected.values.single() shouldContain "C-6"
        snapshot() shouldBe before
    }

    @Test
    fun `a stored canonical document of another shape is refused and nothing is written`() {
        val broken = """{"lines":{"value":[{"lineNumber":1}],"classification":"content"}}"""
        shouldThrow<CanonicalShapeException> { create(broken) }
        shouldThrow<CanonicalShapeException> { create("""{"lines":{"value":{"a":1},"classification":"c"}}""") }
        val oneParty = """{"parties":{"value":{"supplier":{}},"classification":"c"}}"""
        shouldThrow<CanonicalShapeException> { create(oneParty) }
        snapshot().values.forEach { it.shouldBeEmpty() }
    }
}
