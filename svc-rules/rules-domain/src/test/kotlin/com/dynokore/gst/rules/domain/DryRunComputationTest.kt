package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.dryrun.ChangeType
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.ItemReading
import com.dynokore.gst.rules.domain.dryrun.SampleItems
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The delta computation of a dry run, without ports (INV-REQ-772, INV-REQ-686, INV-REQ-703). */
class DryRunComputationTest {
    private val t = LocalDate.of(2026, 10, 1)
    private val old = DocumentOutcome("SR")
    private val new = DocumentOutcome("TX")

    private fun uuid(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun doc(
        n: Int,
        period: String,
        date: LocalDate,
        function: String = "BILL_FOR_PAYMENT",
        preceding: LocalDate? = null,
    ) = SampledDocument(
        1,
        n,
        uuid(n),
        period,
        "E",
        date,
        "C",
        function,
        preceding,
        null,
        false,
        BigDecimal("100.00"),
        BigDecimal("9.00"),
    )

    private fun SampledDocument.flagged() =
        copy(sgdValuesAbsent = true, taxableValue = BigDecimal("0.00"), gstValue = BigDecimal("0.00"))

    private fun SampledDocument.money(
        taxable: String,
        gst: String,
    ) = copy(taxableValue = BigDecimal(taxable), gstValue = BigDecimal(gst))

    private fun delta(
        vararg docs: SampledDocument,
        unchanged: Set<String> = emptySet(),
    ): DryRunDelta =
        DryRunComputation.delta(
            docs.map { Triple(it, old, if (it.docUuid in unchanged) old else new) },
            t,
        )

    @Test
    fun `INV-AC-10768 affected documents are grouped by period and the unaffected one is in no group`() {
        val a1 = doc(1, "P1", t.minusDays(20))
        val a2 = doc(2, "P1", t.minusDays(1)) // look-alike: one day on its own side of T
        val b1 = doc(3, "P2", t.plusDays(2))
        val u1 = doc(4, "P2", t.plusDays(3))
        val d = delta(a1, a2, b1, u1, unchanged = setOf(u1.docUuid))
        d.byPeriod.map { it.periodRef to it.documentCount } shouldBe listOf("P1" to 2, "P2" to 1)
        d.byPeriod.sumOf { it.documentCount } shouldBe 3
        d.changedCount shouldBe 3
        d.documents.none { it.docUuid == u1.docUuid } shouldBe true
    }

    @Test
    fun `INV-AC-10769 the straddling credit note is in its own list and in no other`() {
        val cn1 = doc(1, "P2", t.plusDays(1), "ADJUSTMENT", t.minusDays(5))
        val cn2 = doc(2, "P2", t.plusDays(1), "ADJUSTMENT", t.plusDays(1))
        val cn3 = doc(3, "P1", t.minusDays(1), "ADJUSTMENT", t.minusDays(9))
        val i1 = doc(4, "P2", t.plusDays(2))
        val d = delta(cn1, cn2, cn3, i1)
        d.straddlingCreditNotes.map { it.docUuid } shouldBe listOf(cn1.docUuid)
        d.sample.map { it.docUuid } shouldBe listOf(cn2.docUuid, cn3.docUuid, i1.docUuid)
        (d.sample.size + d.straddlingCreditNotes.size) shouldBe d.changedCount
    }

    @Test
    fun `INV-AC-748 two plus one changed documents in two periods give counts 2 and 1 summing to changedCount`() {
        val cn = doc(3, "P-B", t.plusDays(40), "ADJUSTMENT", t.minusDays(3))
        val d = delta(doc(1, "P-A", t), doc(2, "P-A", t), cn)
        d.byPeriod.map { it.periodRef to it.documentCount } shouldBe listOf("P-A" to 2, "P-B" to 1)
        d.byPeriod.sumOf { it.documentCount } shouldBe d.changedCount
        d.straddlingCreditNotes.single().docUuid shouldBe uuid(3)
    }

    @Test
    fun `INV-AC-748 straddling look-alikes are not listed`() {
        val d =
            delta(
                doc(1, "P", t.plusDays(1), "ADJUSTMENT", t.plusDays(2)),
                doc(2, "P", t.minusDays(2), "ADJUSTMENT", t.minusDays(3)),
                doc(3, "P", t.plusDays(1), "ADJUSTMENT", t), // preceding on effectiveFrom is the same side
                doc(4, "P", t.plusDays(1), "BILL_FOR_PAYMENT", t.minusDays(2)),
                doc(5, "P", t.plusDays(1), "ADJUSTMENT", null),
            )
        d.straddlingCreditNotes shouldBe emptyList()
        d.sample.size shouldBe 5
    }

    @Test
    fun `INV-AC-748 byPeriod follows the earliest transaction date, not the periodRef string`() {
        val d = delta(doc(1, "P-10", LocalDate.of(2026, 10, 1)), doc(2, "P-9", LocalDate.of(2026, 9, 1)))
        d.byPeriod.map { it.periodRef } shouldBe listOf("P-9", "P-10")
    }

    @Test
    fun `byPeriod items with equal earliest dates are ordered by periodRef ascending`() {
        val d = delta(doc(1, "P-B", t), doc(2, "P-A", t))
        d.byPeriod.map { it.periodRef } shouldBe listOf("P-A", "P-B")
    }

    @Test
    fun `INV-AC-748 a flagged document is counted but left out of the sums and listed with 0 00`() {
        val flagged = doc(1, "P", t).flagged()
        val d = delta(flagged, doc(2, "P", t).money("1000.00", "90.00"))
        d.changeTypeTotals.sumOf { it.documentCount } shouldBe 2
        d.changeTypeTotals.first().let { (it.taxableValue to it.gstValue) shouldBe ("1000.00" to "90.00") }
        d.changeTypeTotals.first().valuesAbsentCount shouldBe 1
        d.byPeriod.single().let { (it.documentCount to it.valuesAbsentCount) shouldBe (2 to 1) }
        d.sample.first { it.docUuid == flagged.docUuid }.sgdValuesAbsent shouldBe true
        d.sample.first { it.docUuid != flagged.docUuid }.sgdValuesAbsent shouldBe null
    }

    @Test
    fun `change types follow the refusal of the old and new outcome`() {
        val refused = DocumentOutcome("RUL-009", "BLOCK")
        DryRunComputation.changeTypeOf(old, refused) shouldBe ChangeType.NEWLY_REFUSED
        DryRunComputation.changeTypeOf(refused, old) shouldBe ChangeType.NEWLY_ACCEPTED
        DryRunComputation.changeTypeOf(old, DocumentOutcome("RUL-009", "WARN")) shouldBe ChangeType.CODE_CHANGED
        DryRunComputation.changeTypeOf(old, old) shouldBe null
    }

    @Test
    fun `an item is attributable only with periodRef and a UUID docUuid`() {
        val ok =
            mapOf(
                "periodRef" to "P",
                "docUuid" to uuid(1),
                "entityRef" to "E",
                "transactionDate" to "2026-10-01",
                "internalTaxCode" to "C",
                "documentFunction" to "BILL_FOR_PAYMENT",
                "taxableValue" to "1.00",
                "gstValue" to "0.09",
            )
        SampleItems.read(1, 1, ok).shouldBeInstanceOf<ItemReading.Read>()
        listOf(
            ok - "periodRef",
            ok + ("periodRef" to " "),
            ok + ("periodRef" to "x".repeat(33)),
            ok + ("periodRef" to 3),
            ok - "periodRef" + ("periodReference" to "P"),
            ok - "docUuid",
            ok - "docUuid" + ("docUUID" to uuid(1)),
            ok - "docUuid" + ("documentUuid" to uuid(1)),
            ok + ("docUuid" to "not-a-uuid"),
            ok + ("docUuid" to 7),
        ).forEach {
            SampleItems
                .read(2, 5, it)
                .shouldBeInstanceOf<ItemReading.Refused>()
                .refusal
                .toString()
                .startsWith("page 2, item 5: ") shouldBe true
        }
    }
}
