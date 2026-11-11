package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.dryrun.ChangeType
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.ItemReading
import com.dynokore.gst.rules.domain.dryrun.SampleItems
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.domain.dryrun.SupplyTypeTotal
import io.kotest.assertions.throwables.shouldThrow
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

    private fun typed(
        n: Int,
        type: String?,
        taxable: String = "100.00",
        gst: String = "9.00",
    ) = doc(n, "P1", t.plusDays(1)).copy(supplyType = type).money(taxable, gst)

    private fun totals(vararg docs: SampledDocument): Pair<DryRunDelta, List<SupplyTypeTotal>> {
        val d = delta(*docs)
        val r = DryRunComputation.supplyTypeTotals(d.documents)
        r.sumOf { it.documentCount } shouldBe d.changedCount
        r.sumOf { BigDecimal(it.taxableValue) }.setScale(2) shouldBe
            d.changeTypeTotals.sumOf { BigDecimal(it.taxableValue) }.setScale(2)
        r.sumOf { BigDecimal(it.gstValue) }.setScale(2) shouldBe
            d.changeTypeTotals.sumOf { BigDecimal(it.gstValue) }.setScale(2)
        return d to r
    }

    @Test
    fun `INV-AC-20339 five members give five items in the stated order`() {
        val (_, r) =
            totals(
                typed(1, "IMPORTS"),
                typed(2, "OUT_OF_SCOPE"),
                typed(3, "EXEMPT"),
                typed(4, "ZERO_RATED"),
                typed(5, "STANDARD_RATED"),
            )
        r.map { it.supplyType } shouldBe listOf("STANDARD_RATED", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "IMPORTS")
    }

    @Test
    fun `INV-AC-20339 two supply types give their pairs and no third item`() {
        val (d, r) =
            totals(
                typed(1, "ZERO_RATED", "250.00", "22.50"),
                typed(2, "STANDARD_RATED", "1000.00", "90.00"),
            )
        r shouldBe
            listOf(
                SupplyTypeTotal("STANDARD_RATED", 1, "1000.00", "90.00", 0),
                SupplyTypeTotal("ZERO_RATED", 1, "250.00", "22.50", 0),
            )
        r.none { x -> d.documents.any { it.docUuid in x.toString() } } shouldBe true
    }

    @Test
    fun `INV-AC-20339 a flagged document counts in documentCount and valuesAbsentCount and in neither sum`() {
        val flagged = typed(2, "STANDARD_RATED", "777.00", "70.00").copy(sgdValuesAbsent = true)
        val (_, r) = totals(typed(1, "STANDARD_RATED", "10.00", "0.90"), flagged)
        r shouldBe listOf(SupplyTypeTotal("STANDARD_RATED", 2, "10.00", "0.90", 1))
    }

    @Test
    fun `INV-AC-20339 look-alikes of ZERO_RATED are their own items after the five`() {
        val (_, r) =
            totals(typed(1, "zero_rated"), typed(2, "ZERO-RATED"), typed(3, "ZERO_RATED"), typed(4, "IMPORTS"))
        r.map { it.supplyType } shouldBe listOf("ZERO_RATED", "IMPORTS", "ZERO-RATED", "zero_rated")
        r.map { it.documentCount } shouldBe listOf(1, 1, 1, 1)
    }

    @Test
    fun `INV-AC-20339 a member outside the five is carried unchanged and others sort ascending`() {
        val (_, r) = totals(typed(1, "ZZ_OTHER"), typed(2, "DEEMED_SUPPLY"), typed(3, "EXEMPT"))
        r.map { it.supplyType } shouldBe listOf("EXEMPT", "DEEMED_SUPPLY", "ZZ_OTHER")
    }

    @Test
    fun `INV-AC-20339 an empty delta gives an empty list`() {
        DryRunComputation.supplyTypeTotals(emptyList()) shouldBe emptyList()
        totals().second shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20339 a changed document without a supply type is a fault naming it`() {
        val d = delta(typed(1, null))
        val e = shouldThrow<IllegalStateException> { DryRunComputation.supplyTypeTotals(d.documents) }
        e.message!!.contains(uuid(1)) shouldBe true
    }

    @Test
    fun `INV-AC-20339 a blank, whitespace-only or missing supplyType is read as null`() {
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

        fun typeOf(raw: Map<String, Any?>) =
            SampleItems
                .read(1, 1, raw)
                .shouldBeInstanceOf<ItemReading.Read>()
                .document.supplyType
        typeOf(ok + ("supplyType" to "")) shouldBe null
        typeOf(ok + ("supplyType" to " \t ")) shouldBe null
        typeOf(ok) shouldBe null
        typeOf(ok + ("supplyType" to 5)) shouldBe null
        typeOf(ok + ("supplyTypes" to "ZERO_RATED")) shouldBe null
        typeOf(ok + ("supplyType" to "ZERO_RATED")) shouldBe "ZERO_RATED"
        typeOf(ok + ("supplyType" to "DEEMED_SUPPLY")) shouldBe "DEEMED_SUPPLY"
    }
}
