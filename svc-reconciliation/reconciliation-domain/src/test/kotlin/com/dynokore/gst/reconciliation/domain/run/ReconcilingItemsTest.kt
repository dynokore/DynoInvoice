package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxAggregator
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.DocumentLine
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReconcilingItemsTest {
    private fun d(s: String) = BigDecimal(s)

    private val entries = listOf(MappingEntry("SR", Direction.SALE, listOf(BoxFeed("B1", Basis.TAXABLE_AMOUNT))))

    private fun sgd(
        number: Int,
        amount: String,
    ) = SgdLine(number, "SR", d(amount), d("0.00"))

    private fun version(
        number: Int,
        acknowledged: Boolean,
        vararg lines: SgdLine,
        verdict: String? = null,
    ) = RunDocumentVersion(
        number,
        acknowledged,
        DocumentFacts(verdict, null),
        Direction.SALE,
        SgdAllocation.Allocated(lines.toList()),
    )

    private val documents =
        listOf(
            RunDocument(
                "D2",
                listOf(version(1, false, sgd(1, "-10.00"))),
            ),
            RunDocument(
                "D1",
                listOf(
                    version(1, false, sgd(1, "5.00")),
                    version(2, false, sgd(3, "0.00"), sgd(2, "40.00"), sgd(1, "60.00"), verdict = "EXCLUDED_TXN"),
                ),
            ),
            RunDocument("D3", listOf(version(1, true, sgd(1, "999.00")))),
        )

    private fun outcome(docs: List<RunDocument> = documents) =
        ReconciliationRunner
            .run(ReturnFigures(1, mapOf("B1" to d("100.00"))), entries, docs)
            .shouldBeInstanceOf<RunOutcome.Completed>()

    private fun item(
        doc: String,
        version: Int,
        line: Int,
        amount: String,
        vararg refs: String,
    ) = ReconcilingItem("B1", doc, version, line, d(amount), refs.toList())

    @Test
    fun `INV-AC-24461 the outcome holds exactly the items of D1 and D2 in order with their references`() {
        val items = outcome().reconcilingItems
        items shouldBe
            listOf(
                item("D1", 2, 1, "60.00", "D1"),
                item("D1", 2, 2, "40.00", "D1"),
                item("D2", 1, 1, "-10.00", "D2"),
            )
        items.map { it.evidenceRefs } shouldBe listOf(listOf("D1"), listOf("D1"), listOf("D2"))
    }

    @Test
    fun `INV-AC-24461 the classified variance of B1 links D1 and not D2`() {
        val linked = outcome().result.classifiedVariances.flatMap { it.linkedRecords }
        linked shouldBe listOf("D1")
    }

    @Test
    fun `INV-AC-24461 the acknowledged document D3 and the zero line give no item`() {
        val items = outcome().reconcilingItems
        items.none { it.docUuid == "D3" } shouldBe true
        items.none { it.docUuid == "D1" && it.lineNumber == 3 } shouldBe true
        items.none { it.amount.signum() == 0 } shouldBe true
    }

    @Test
    fun `INV-AC-24461 the items of a document sum to its evidence amount for the Box`() {
        val o = outcome()
        o.reconcilingItems.filter { it.docUuid == "D1" }.sumOf { it.amount } shouldBe d("100.00")
        o.reconcilingItems.filter { it.docUuid == "D2" }.sumOf { it.amount } shouldBe d("-10.00")
    }

    @Test
    fun `INV-AC-24461 the items are sorted by box, docUuid, version, lineNumber`() {
        val items = outcome(documents.reversed()).reconcilingItems
        items shouldBe items.sortedWith(compareBy({ it.box }, { it.docUuid }, { it.version }, { it.lineNumber }))
        items.size shouldBe 3
    }

    @Test
    fun `INV-AC-24461 a fake CompletedRuns receives the same list`() {
        val received = mutableListOf<List<ReconcilingItem>>()
        val record = { o: RunOutcome.Completed -> received += o.reconcilingItems }
        val o = outcome()
        record(o)
        received.single() shouldBe o.reconcilingItems
    }

    @Test
    fun `INV-AC-24461 an item needs a non-empty list without a blank reference`() {
        shouldThrow<IllegalArgumentException> { item("D1", 1, 1, "1.00") }
        shouldThrow<IllegalArgumentException> { item("D1", 1, 1, "1.00", " ") }
        outcome().reconcilingItems.all {
            it.evidenceRefs.isNotEmpty() &&
                it.evidenceRefs.none(
                    String::isBlank,
                )
        } shouldBe
            true
    }

    @Test
    fun `INV-AC-24461 the contributions of a Box sum to its aggregate`() {
        val lines =
            documents.flatMap { doc ->
                doc.versions.flatMap { v ->
                    (v.allocation as SgdAllocation.Allocated).lines.map {
                        DocumentLine(
                            doc.docUuid,
                            v.version,
                            v.direction,
                            it,
                        )
                    }
                }
            }
        BoxAggregator.contributions(entries, lines).sumOf { it.amount } shouldBe
            BoxAggregator.aggregate(entries, lines).boxes.getValue("B1")
    }
}
