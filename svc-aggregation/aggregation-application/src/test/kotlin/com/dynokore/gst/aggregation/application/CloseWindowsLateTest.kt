package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-24210 with fake ports: the late supplementary of a transmitted part (INV-REQ-439, INV-REQ-438). */
class CloseWindowsLateTest {
    private val day = LocalDate.of(2027, 3, 10)
    private val window = OpenWindow("ent-1", "bu-1", AggregateType.POS, AggregationWindow(day, day))
    private val part = WindowPart(day, day, "2027-03")
    private val tick = LocalDate.of(2027, 3, 12)

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
        ) = rows.firstOrNull { it.aggregate.sourceKey == sourceKey && it.aggregate.sourceRevision == sourceRevision }

        override fun highestRevision(
            entityRef: String,
            sourceKey: String,
        ) = rows.filter { it.aggregate.sourceKey == sourceKey }.maxOfOrNull { it.aggregate.sourceRevision }

        override fun nextSequence() = ++sequence

        override fun store(aggregate: NewAggregate) = StoredAggregate(aggregate, UUID.randomUUID()).also { rows += it }
    }

    /** A closed part with a sales aggregate [transmitted] or not and late transactions per kind. */
    private class Windows(
        private val window: OpenWindow,
        private val part: WindowPart,
    ) : WindowRepository {
        var transmitted = false
        val late = mutableMapOf(false to 0, true to 0)
        val assigned = mutableMapOf<UUID, Pair<Boolean, Int>>()

        override fun place(
            entityRef: String,
            businessUnitRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: Cadence,
        ) = Placement(AggregationWindow.containing(cadence, date), WindowState.CLOSED)

        override fun openWindowsStartingOnOrBefore(date: LocalDate) = emptyList<OpenWindow>()

        override fun isPartClosed(
            window: OpenWindow,
            part: WindowPart,
        ) = true

        override fun closePart(
            window: OpenWindow,
            part: WindowPart,
            aggregateId: UUID,
        ) = Unit

        override fun recordLate(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
        ) = false

        override fun closedPartsWithLateTransactions() =
            if (!transmitted) {
                emptyList()
            } else {
                listOf(false, true)
                    .filter {
                        late.getValue(it) > 0
                    }.map { LatePart(window, part, it) }
            }

        override fun lateSupplementaryCount(late: LatePart) =
            assigned.values.count {
                it.first == late.isCreditNote &&
                    it.second > 0
            }

        override fun assignLate(
            late: LatePart,
            aggregateId: UUID,
        ) {
            assigned[aggregateId] = late.isCreditNote to this.late.getValue(late.isCreditNote)
            this.late[late.isCreditNote] = 0
        }
    }

    private class Fixture(
        window: OpenWindow,
        part: WindowPart,
    ) {
        val repo = AggregateRepo()
        val windows = Windows(window, part)
        val run = CloseWindows(windows, { _, _, _ -> PeriodSpans.Unknown }, IntakeAggregate(Codes(), repo)) { }
    }

    private fun fixture() = Fixture(window, part)

    private fun Fixture.original() = repo.store(newOriginal())

    private fun newOriginal() =
        NewAggregate(
            "ent-1",
            "bu-1",
            AggregateType.POS,
            day,
            day,
            "window:ent-1:POS:$day:$day",
            1,
            1,
            "E",
            "B",
            "ORIGINAL",
            day,
            day,
        )

    @Test
    fun `INV-AC-24210 a transmitted part makes one supplementary for sales and one for credit notes, never netted`() {
        val f = fixture()
        val a = f.original()
        f.windows.transmitted = true
        f.windows.late[false] = 1
        f.windows.late[true] = 1

        f.run.execute(tick)

        val made = f.repo.rows.filter { it.aggregate.isSupplementary }
        made.size shouldBe 2
        made.map { it.aggregate.isCreditNote }.toSet() shouldBe setOf(false, true)
        made.none { it.aggregateId == a.aggregateId } shouldBe true
        made.map { it.aggregate.documentNumber }.toSet().size shouldBe 2
        made.map { it.aggregate.sequence } shouldBe listOf(1L, 2L)
        made.map { it.aggregate.sourceKey } shouldBe
            listOf("window-late:ent-1:POS:$day:$day:1", "window-late-credit:ent-1:POS:$day:$day:1")
        made.all { it.aggregate.sourceRevision == 1L } shouldBe true
        f.windows.assigned.keys shouldBe made.map { it.aggregateId }.toSet()
    }

    @Test
    fun `INV-AC-24210 a part whose aggregate is not transmitted makes nothing until the mark is set`() {
        val f = fixture()
        f.original()
        f.windows.late[false] = 1

        f.run.execute(tick)
        f.repo.rows.size shouldBe 1

        f.windows.transmitted = true
        f.run.execute(tick)
        f.repo.rows.size shouldBe 2
        f.repo.rows[1]
            .aggregate.isSupplementary shouldBe true
    }

    @Test
    fun `INV-AC-24210 a second firing replays and creates nothing`() {
        val f = fixture()
        f.original()
        f.windows.transmitted = true
        f.windows.late[false] = 1

        f.run.execute(tick)
        f.run.execute(tick)

        f.repo.rows.size shouldBe 2
        f.windows.assigned.size shouldBe 1
    }

    @Test
    fun `INV-AC-24210 a new late transaction after a filled supplementary gets key 2`() {
        val f = fixture()
        f.original()
        f.windows.transmitted = true
        f.windows.late[false] = 1
        f.run.execute(tick)
        f.windows.late[false] = 2
        f.run.execute(tick)

        f.repo.rows
            .map { it.aggregate.sourceKey }
            .last() shouldBe "window-late:ent-1:POS:$day:$day:2"
        f.repo.rows.size shouldBe 3
    }

    @Test
    fun `INV-AC-24210 a part left half done is finished by the next run under the same key`() {
        val f = fixture()
        f.original()
        f.windows.transmitted = true
        f.windows.late[false] = 1
        // a failed run stored the aggregate and died before the assignment
        f.repo.store(
            newOriginal().copy(sourceKey = "window-late:ent-1:POS:$day:$day:1", isSupplementary = true, sequence = 9),
        )

        f.run.execute(tick)

        f.repo.rows.size shouldBe 2
        f.windows.assigned.keys shouldBe setOf(f.repo.rows[1].aggregateId)
    }

    @Test
    fun `INV-AC-24210 the transmitted aggregate is not touched`() {
        val f = fixture()
        val a = f.original().copy(transmittedAt = Instant.parse("2027-03-11T00:00:00Z"))
        f.windows.transmitted = true
        f.windows.late[false] = 1
        f.run.execute(tick)
        f.repo.rows[0].aggregate shouldBe a.aggregate
    }
}
