package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import com.dynokore.gst.reconciliation.domain.run.ReturnFigures
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunDocumentVersion
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AssembleRunReconcilingItemsTest {
    private fun d(s: String) = BigDecimal(s)

    private val entries = listOf(MappingEntry("SR", Direction.SALE, listOf(BoxFeed("B1", Basis.TAXABLE_AMOUNT))))
    private val clock = Clock.fixed(Instant.parse("2026-04-15T01:02:03Z"), ZoneOffset.UTC)

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

    private fun sgd(
        number: Int,
        amount: String,
    ) = SgdLine(number, "SR", d(amount), d("0.00"))

    private val documents =
        listOf(
            RunDocument("D2", listOf(version(1, false, sgd(1, "-10.00")))),
            RunDocument(
                "D1",
                listOf(version(2, false, sgd(3, "0.00"), sgd(2, "40.00"), sgd(1, "60.00"), verdict = "EXCLUDED_TXN")),
            ),
            RunDocument("D3", listOf(version(1, true, sgd(1, "999.00")))),
        )

    private val expected =
        listOf(
            ReconcilingItem("B1", "D1", 2, 1, d("60.00"), listOf("D1")),
            ReconcilingItem("B1", "D1", 2, 2, d("40.00"), listOf("D1")),
            ReconcilingItem("B1", "D2", 1, 1, d("-10.00"), listOf("D2")),
        )

    private class Fakes(
        val figures: ReturnFigures?,
        val entries: List<MappingEntry>,
        val docs: List<RunDocument>,
    ) {
        val recorded = mutableListOf<RunOutcome.Completed>()
        val runs =
            CompletedRuns { _, e, p, outcome, at ->
                recorded += outcome
                RecordedRun("run-${recorded.size}", e, p, outcome.returnVersion, at)
            }

        fun useCase(clock: Clock) =
            AssembleRun(
                { _, _, _ -> figures },
                { _, _, _ -> entries },
                { _, _, _ -> docs },
                runs,
                clock,
            )
    }

    private fun fixture(figures: ReturnFigures? = ReturnFigures(1, mapOf("B1" to d("100.00")))) =
        Fakes(figures, entries, documents)

    @Test
    fun `INV-AC-24461 the CompletedRuns fake stores the three expected items in order with their evidence lists`() {
        val f = fixture()
        f.useCase(clock).execute("T1", "E1", "2026-Q1")
        f.recorded.single().reconcilingItems shouldBe expected
    }

    @Test
    fun `INV-AC-24461 the returned Completed outcome carries the stored list`() {
        val f = fixture()
        val result = f.useCase(clock).execute("T1", "E1", "2026-Q1").shouldBeInstanceOf<AssembledRun.Completed>()
        result.outcome.reconcilingItems shouldBe f.recorded.single().reconcilingItems
        result.outcome.reconcilingItems shouldBe expected
    }

    @Test
    fun `INV-AC-24461 with no current return nothing is recorded`() {
        val f = fixture(figures = null)
        f.useCase(clock).execute("T1", "E1", "2026-Q1") shouldBe AssembledRun.NoReturn("PER-008")
        f.recorded shouldBe emptyList()
    }
}
