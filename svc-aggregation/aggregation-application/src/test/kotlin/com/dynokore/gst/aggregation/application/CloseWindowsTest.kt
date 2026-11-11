package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class CloseWindowsTest {
    private fun d(
        m: Int,
        day: Int,
    ) = LocalDate.of(2027, m, day)

    private class Codes : ShortCodes {
        override fun entityCode(entityRef: String) = "E"

        override fun businessUnitCode(businessUnitRef: String) = "B"
    }

    private class AggregateRepo : AggregateRepository {
        val rows = mutableListOf<StoredAggregate>()
        private var sequence = 0L

        override fun findByIntakeKey(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
        ) = rows.firstOrNull {
            it.aggregate.entityRef == entityRef &&
                it.aggregate.sourceKey == sourceKey &&
                it.aggregate.sourceRevision == sourceRevision
        }

        override fun highestRevision(
            entityRef: String,
            sourceKey: String,
        ) = rows.filter { it.aggregate.sourceKey == sourceKey }.maxOfOrNull { it.aggregate.sourceRevision }

        override fun nextSequence() = ++sequence

        override fun store(aggregate: NewAggregate) = StoredAggregate(aggregate, UUID.randomUUID()).also { rows += it }
    }

    private class Windows(
        val open: MutableList<OpenWindow>,
    ) : WindowRepository {
        val closedParts = mutableListOf<Pair<OpenWindow, WindowPart>>()

        override fun place(
            entityRef: String,
            businessUnitRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: Cadence,
        ) = Placement(AggregationWindow.containing(cadence, date), WindowState.OPEN)

        override fun isPartClosed(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
        ) = closedParts.any { it.first == window && it.second == part }

        override fun openWindowsStartingOnOrBefore(date: LocalDate) =
            open.filter { w ->
                w.window.start <= date &&
                    closedParts.filter { it.first == w }.sumOf {
                        it.second.end.toEpochDay() -
                            it.second.start.toEpochDay() +
                            1
                    } <
                    w.window.end.toEpochDay() - w.window.start.toEpochDay() + 1
            }

        override fun closePart(
            window: OpenWindow,
            part: WindowPart,
            aggregateId: UUID,
        ) {
            closedParts += window to part
        }

        override fun recordLate(window: OpenWindow) = Unit
    }

    private class Published : WindowChangePublisher {
        val changes = mutableListOf<WindowChange>()

        override fun changed(change: WindowChange) {
            changes += change
        }
    }

    private val monthly =
        listOf(
            PeriodSpan(d(3, 1), d(3, 31), "2027-03"),
            PeriodSpan(d(4, 1), d(4, 30), "2027-04"),
            PeriodSpan(d(5, 1), d(5, 31), "2027-05"),
        )

    private fun window(
        cadence: Cadence,
        date: LocalDate,
        type: AggregateType = AggregateType.PCP,
    ) = OpenWindow("ent-1", "bu-1", type, AggregationWindow.containing(cadence, date))

    private class Fixture(
        open: List<OpenWindow>,
        spans: PeriodSpans,
    ) {
        val repo = AggregateRepo()
        val windows = Windows(open.toMutableList())
        val published = Published()
        val run =
            CloseWindows(windows, { _, _, _ -> spans }, IntakeAggregate(Codes(), repo), published)
    }

    @Test
    fun `INV-AC-1573 the weekly windows of April close one aggregate per part and split at 30 April`() {
        val weeks = listOf(d(4, 1), d(4, 8), d(4, 15), d(4, 22), d(4, 28)).map { window(Cadence.WEEKLY, it) }
        val f = Fixture(weeks, PeriodSpans.Known(monthly))
        val result = f.run.execute(d(5, 2))
        result.held.shouldBeEmpty()
        result.closed.size shouldBe 7
        f.repo.rows.size shouldBe 7
        f.repo.rows
            .map { it.aggregate.periodStart to it.aggregate.periodEnd }
            .filter { it.first <= d(4, 30) && it.second > d(4, 30) }
            .shouldBeEmpty()
        f.repo.rows.map { it.aggregate.periodEnd } shouldBe
            listOf(d(3, 31), d(4, 4), d(4, 11), d(4, 18), d(4, 25), d(4, 30), d(5, 2))
    }

    @Test
    fun `INV-AC-1573 the default entity closes one PCP window for April as one aggregate`() {
        val f = Fixture(listOf(window(Cadence.MONTHLY, d(4, 10))), PeriodSpans.Known(monthly))
        val result = f.run.execute(d(4, 30))
        result.closed.size shouldBe 1
        val single =
            f.repo.rows
                .single()
                .aggregate
        (single.periodStart to single.periodEnd) shouldBe (d(4, 1) to d(4, 30))
    }

    @Test
    fun `a window is split at the period end and each part is closed and published once with the part dates`() {
        val f = Fixture(listOf(window(Cadence.WEEKLY, d(3, 30))), PeriodSpans.Known(monthly))
        f.run.execute(d(4, 4))
        f.repo.rows.map { it.aggregate.periodStart to it.aggregate.periodEnd } shouldBe
            listOf(d(3, 29) to d(3, 31), d(4, 1) to d(4, 4))
        f.published.changes.map { it.part.start to it.part.end } shouldBe
            listOf(d(3, 29) to d(3, 31), d(4, 1) to d(4, 4))
        f.published.changes.forEach { it.state shouldBe WindowState.CLOSED }
        f.published.changes.map { it.aggregateId } shouldBe f.repo.rows.map { it.aggregateId }
    }

    @Test
    fun `the source key of a part names entity, type and part dates at revision 1`() {
        val f = Fixture(listOf(window(Cadence.DAILY, d(4, 7), AggregateType.POS)), PeriodSpans.Known(monthly))
        f.run.execute(d(4, 7))
        f.repo.rows.single().aggregate.let {
            it.sourceKey shouldBe "window:ent-1:POS:2027-04-07:2027-04-07"
            it.sourceRevision shouldBe 1
        }
    }

    @Test
    fun `a window that ends after the tick stays open`() {
        val f = Fixture(listOf(window(Cadence.MONTHLY, d(4, 10))), PeriodSpans.Known(monthly))
        f.run
            .execute(d(4, 29))
            .closed
            .shouldBeEmpty()
        f.repo.rows.shouldBeEmpty()
    }

    @Test
    fun `a second firing of the same tick creates and publishes nothing again`() {
        val windows = listOf(window(Cadence.WEEKLY, d(3, 30)))
        val f = Fixture(windows, PeriodSpans.Known(monthly))
        f.run.execute(d(4, 4))
        // a repository that still lists the window but has every part closed: nothing is closed or published again
        val spans = PeriodSpans.Known(monthly)
        val again =
            CloseWindows(StillOpen(windows), { _, _, _ -> spans }, IntakeAggregate(Codes(), f.repo), f.published)
        again.execute(d(4, 4)).closed.shouldBeEmpty()
        f.repo.rows.size shouldBe 2
        f.published.changes.size shouldBe 2
    }

    @Test
    fun `a part whose aggregate exists but was never closed is closed and published by the next run`() {
        val windows = listOf(window(Cadence.WEEKLY, d(3, 30)))
        val f = Fixture(windows, PeriodSpans.Known(monthly))
        f.run.execute(d(4, 4))
        f.windows.closedParts.clear()
        f.published.changes.clear()
        val again = f.run.execute(d(4, 4))
        again.closed.size shouldBe 2
        f.published.changes.size shouldBe 2
        f.repo.rows.size shouldBe 2
        f.windows.closedParts.size shouldBe 2
    }

    private class StillOpen(
        private val open: List<OpenWindow>,
    ) : WindowRepository {
        override fun place(
            entityRef: String,
            businessUnitRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: Cadence,
        ) = error("not used")

        override fun isPartClosed(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
        ) = true

        override fun openWindowsStartingOnOrBefore(date: LocalDate) = open

        override fun closePart(
            window: OpenWindow,
            part: WindowPart,
            aggregateId: UUID,
        ) = error("a replayed part is not closed again")

        override fun recordLate(window: OpenWindow) = Unit
    }

    @Test
    fun `a window with no known span is held and nothing is created`() {
        val f = Fixture(listOf(window(Cadence.MONTHLY, d(4, 10))), PeriodSpans.Unknown)
        val result = f.run.execute(d(5, 1))
        result.held.size shouldBe 1
        result.closed.shouldBeEmpty()
        f.repo.rows.shouldBeEmpty()
        f.published.changes.shouldBeEmpty()
    }

    @Test
    fun `a window with an uncovered date is held and nothing is created`() {
        val gap = listOf(PeriodSpan(d(3, 1), d(3, 31), "2027-03"), PeriodSpan(d(4, 5), d(4, 30), "2027-04"))
        val f = Fixture(listOf(window(Cadence.WEEKLY, d(3, 30))), PeriodSpans.Known(gap))
        val result = f.run.execute(d(4, 4))
        result.held.size shouldBe 1
        f.repo.rows.shouldBeEmpty()
        f.published.changes.shouldBeEmpty()
        f.windows.closedParts.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-20428 a part ending on the tick closes alone and the later part stays open`() {
        val f = Fixture(listOf(window(Cadence.WEEKLY, d(3, 30))), PeriodSpans.Known(monthly))
        val result = f.run.execute(d(3, 31))
        result.closed.size shouldBe 1
        result.closed.single().let {
            (it.part.start to it.part.end) shouldBe (d(3, 29) to d(3, 31))
            it.state shouldBe WindowState.CLOSED
        }
        f.repo.rows
            .single()
            .aggregate.periodEnd shouldBe d(3, 31)
        f.published.changes.size shouldBe 1
        f.windows.closedParts.size shouldBe 1
        f.repo.rows.none { it.aggregate.periodStart == d(4, 1) } shouldBe true
    }

    @Test
    fun `INV-AC-20428 the later part closes on the next tick and the first is not closed again`() {
        val f = Fixture(listOf(window(Cadence.WEEKLY, d(3, 30))), PeriodSpans.Known(monthly))
        f.run.execute(d(3, 31))
        val firstId =
            f.repo.rows
                .single()
                .aggregateId
        val second = f.run.execute(d(4, 4))
        second.closed.map { it.part.start to it.part.end } shouldBe listOf(d(4, 1) to d(4, 4))
        f.repo.rows.size shouldBe 2
        f.published.changes.size shouldBe 2
        f.repo.rows
            .first()
            .aggregateId shouldBe firstId
    }

    @Test
    fun `INV-AC-20428 a tick one day before the part end closes nothing`() {
        val f = Fixture(listOf(window(Cadence.WEEKLY, d(3, 30))), PeriodSpans.Known(monthly))
        f.run
            .execute(d(3, 30))
            .closed
            .shouldBeEmpty()
        f.repo.rows.shouldBeEmpty()
        f.published.changes.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-20428 a window ending after the tick with unknown spans is neither closed nor held`() {
        val f = Fixture(listOf(window(Cadence.MONTHLY, d(4, 10))), PeriodSpans.Unknown)
        val result = f.run.execute(d(4, 29))
        result.held.shouldBeEmpty()
        result.closed.shouldBeEmpty()
        f.repo.rows.shouldBeEmpty()
    }
}
