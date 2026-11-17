package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.run.ReconciliationRunner
import com.dynokore.gst.reconciliation.domain.run.ReturnFigures
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunDocumentVersion
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AssembleRunTest {
    private val tenant = "T1"
    private val entity = "E1"
    private val period = "2026-Q1"
    private val instant = Instant.parse("2026-04-15T01:02:03Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    private val entries =
        listOf(MappingEntry("SR", Direction.SALE, listOf(BoxFeed("1", Basis.TAXABLE_AMOUNT))))

    private fun doc(uuid: String) =
        RunDocument(
            uuid,
            listOf(
                RunDocumentVersion(
                    1,
                    true,
                    DocumentFacts(null, null),
                    Direction.SALE,
                    SgdAllocation.Allocated(listOf(SgdLine(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")))),
                ),
            ),
        )

    private fun figures(box1: String) = ReturnFigures(2, mapOf("1" to BigDecimal(box1)))

    /** Fakes number their calls on one shared counter so the call order is checkable. */
    private class Fakes(
        val figures: ReturnFigures?,
        val entries: List<MappingEntry>,
        val docs: List<RunDocument>,
    ) {
        private var seq = 0
        val order = mutableListOf<String>()
        var returnCalls = 0
        var entryCalls = 0
        var docCalls = 0
        val recorded = mutableListOf<List<Any>>()

        private fun tick(name: String) {
            seq++
            order += "$seq:$name"
        }

        val returns =
            ReturnFiguresSource { t, e, p ->
                returnCalls++
                tick("return")
                check(listOf(t, e, p) == listOf("T1", "E1", "2026-Q1"))
                figures
            }
        val entrySource =
            MappingEntriesSource { _, _, _ ->
                entryCalls++
                tick("entries")
                entries
            }
        val docSource =
            RunDocumentsSource { _, _, _ ->
                docCalls++
                tick("documents")
                docs
            }
        val runs =
            CompletedRuns { t, e, p, outcome, at ->
                tick("record")
                recorded += listOf(t, e, p, outcome, at)
                RecordedRun("run-$seq", e, p, outcome.returnVersion, at)
            }
    }

    private fun useCase(f: Fakes) = AssembleRun(f.returns, f.entrySource, f.docSource, f.runs, clock)

    private fun fixture(
        figures: ReturnFigures?,
        docs: List<RunDocument>,
    ) = Fakes(figures, entries, docs)

    @Test
    fun `INV-AC-24340 fixture A records one run equal to the engine result and reads the return first`() {
        val f = fixture(figures("100.00"), listOf(doc("D1")))
        val result = useCase(f).execute(tenant, entity, period).shouldBeInstanceOf<AssembledRun.Completed>()
        val expected = ReconciliationRunner.run(figures("100.00"), entries, listOf(doc("D1")))
        result.outcome shouldBe expected
        result.outcome.raisesPer003 shouldBe false
        f.recorded shouldBe listOf(listOf(tenant, entity, period, expected, instant))
        result.run.completedAt shouldBe instant
        f.order shouldBe listOf("1:return", "2:entries", "3:documents", "4:record")
    }

    @Test
    fun `INV-AC-24340 fixture B a Box 1 difference raises PER-003 and records once`() {
        val f = fixture(figures("120.00"), listOf(doc("D1")))
        val result = useCase(f).execute(tenant, entity, period).shouldBeInstanceOf<AssembledRun.Completed>()
        result.outcome.raisesPer003 shouldBe true
        f.recorded.size shouldBe 1
        f.recorded.single()[4] shouldBe instant
    }

    @Test
    fun `INV-AC-24340 fixture C no return gives PER-008, reads nothing else and records nothing`() {
        val f = fixture(null, listOf(doc("D1")))
        useCase(f).execute(tenant, entity, period) shouldBe AssembledRun.NoReturn(RunOutcome.PER_008)
        f.returnCalls shouldBe 1
        f.entryCalls shouldBe 0
        f.docCalls shouldBe 0
        f.recorded.size shouldBe 0
        f.order shouldBe listOf("1:return")
    }

    @Test
    fun `INV-AC-24340 fixture D a repeated docUuid is refused and nothing is recorded`() {
        val f = fixture(figures("100.00"), listOf(doc("D1"), doc("D1")))
        shouldThrow<IllegalArgumentException> { useCase(f).execute(tenant, entity, period) }
        f.recorded.size shouldBe 0
    }
}
