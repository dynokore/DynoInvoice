package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.status.EntityStatusEntry
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.BusinessUnitRecord
import com.dynokore.gst.tenancy.application.structure.CodeUse
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.GroupRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.platform.calendarandperiods.BusinessCalendar
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.DueRule
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.StandardWindow
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private val QUARTERLY = FilingFrequency("QUARTERLY")
private val MONTHLY = FilingFrequency("MONTHLY")
private val REGISTRATION: LocalDate = LocalDate.of(2026, 2, 15)
private val SPLIT: LocalDate = LocalDate.of(2026, 7, 1)

private fun pack(): CalendarPack {
    val quarters =
        (2026..2027).flatMap { y ->
            (0 until 4).map {
                StandardWindow(LocalDate.of(y, it * 3 + 1, 1), YearMonth.of(y, it * 3 + 3).atEndOfMonth(), "F5")
            }
        }
    val months =
        (2026..2027).flatMap { y ->
            (1..12).map { StandardWindow(LocalDate.of(y, it, 1), YearMonth.of(y, it).atEndOfMonth(), "F5") }
        }
    return CalendarPack(
        "fixture-1",
        mapOf(QUARTERLY to quarters, MONTHLY to months),
        "F8",
        mapOf("F5" to DueRule(1, true, 0), "F8" to DueRule(3, true, 0)),
        BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
        null,
    )
}

private object Direct : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

/** The one-entity structure store; every draw of the version sequence is recorded. */
private class FakeStructure(
    private val entity: EntityRecord,
) : StructureStore {
    val drawn = mutableListOf<Long>()

    override fun entity(ref: String): EntityRecord? = entity.takeIf { it.ref == ref }

    override fun nextStructureVersion(): Long = (drawn.size + 10L).also { drawn += it }

    override fun group(ref: String): GroupRecord? = null

    override fun groupByName(name: String): GroupRecord? = null

    override fun insertGroup(group: GroupRecord) = Unit

    override fun draftEntity(
        groupRef: String,
        legalName: String,
    ): EntityRecord? = null

    override fun insertEntity(entity: EntityRecord) = Unit

    override fun updateEntityCode(
        ref: String,
        code: String,
        structureVersion: Long,
    ) = Unit

    override fun touchEntity(
        ref: String,
        structureVersion: Long,
    ) = Unit

    override fun businessUnit(ref: String): BusinessUnitRecord? = null

    override fun businessUnitByCode(
        entityRef: String,
        code: String,
    ): BusinessUnitRecord? = null

    override fun insertBusinessUnit(unit: BusinessUnitRecord) = Unit

    override fun moveBusinessUnit(
        ref: String,
        toEntityRef: String,
        structureVersion: Long,
    ) = Unit

    override fun updateBusinessUnit(
        ref: String,
        name: String,
        code: String,
        structureVersion: Long,
    ) = Unit

    override fun codes(): List<CodeUse> = emptyList()
}

private class FakePeriods : PeriodRecordStore {
    private val rows = mutableListOf<Pair<StoredPeriod, Boolean>>()
    private var counter = 0L

    override fun inForce(entityId: UUID): List<StoredPeriod> =
        rows
            .filter {
                !it.second
            }.map { it.first }
            .sortedBy { it.start }

    override fun insert(
        entityId: UUID,
        period: StoredPeriod,
    ) {
        rows += period to false
    }

    override fun supersedeFrom(
        entityId: UUID,
        start: LocalDate,
    ) {
        rows.replaceAll { (p, gone) -> p to (gone || p.start >= start) }
    }

    override fun confirm(
        entityId: UUID,
        periodRef: String,
    ) {
        rows.replaceAll { (p, gone) ->
            (if (!gone && p.periodRef == periodRef) p.copy(confirmation = ConfirmationState.CONFIRMED) else p) to gone
        }
    }

    override fun setDueDate(
        entityId: UUID,
        periodRef: String,
        dueDate: LocalDate,
    ): Long = ++counter

    override fun entityRefsWithRecords(): List<String> = emptyList()

    fun all(): List<Pair<StoredPeriod, Boolean>> = rows.toList()
}

private class FakeHistory : FrequencyHistoryStore {
    val rows = mutableListOf<FrequencyEntry>()

    override fun entries(entityId: UUID): List<FrequencyEntry> = rows.sortedBy { it.effectiveFrom }

    override fun insert(
        entityId: UUID,
        entry: FrequencyEntry,
    ) {
        rows += entry
    }

    override fun setState(
        entityId: UUID,
        id: UUID,
        state: HistoryState,
    ) {
        rows.replaceAll { if (it.id == id) it.copy(state = state) else it }
    }
}

private class FakeProposals : ProposalStore {
    private val periods = mutableMapOf<UUID, List<StoredPeriod>>()
    private val raised = mutableListOf<RaisedItem>()

    override fun proposedPeriods(
        entityId: UUID,
        historyId: UUID,
    ): List<StoredPeriod> = periods[historyId].orEmpty()

    override fun insertProposed(
        entityId: UUID,
        historyId: UUID,
        periods: List<StoredPeriod>,
    ) {
        this.periods[historyId] = periods
    }

    override fun items(entityId: UUID): List<RaisedItem> = raised.toList()

    override fun raise(
        entityId: UUID,
        historyId: UUID,
        code: RaisedCode,
    ) {
        raised += RaisedItem(historyId, code, ItemState.OPEN)
    }

    override fun moveItems(
        entityId: UUID,
        historyId: UUID,
        to: ItemState,
    ) {
        raised.replaceAll { if (it.historyId == historyId) it.copy(state = to) else it }
    }
}

private class Recording : PeriodRecordsChangePublisher {
    val changes = mutableListOf<PeriodRecordsChange>()

    override fun publish(change: PeriodRecordsChange) {
        changes += change
    }
}

private class Setup(
    registration: LocalDate? = REGISTRATION,
    firstEffective: LocalDate = LocalDate.of(2026, 1, 1),
    packAvailable: Boolean = true,
) {
    val entity =
        EntityRecord(UUID.randomUUID(), "ENT-1", "GRP-1", "Acme", "COMPANY", null, null, "AC", "ACTIVE", false, 1)
    val structure = FakeStructure(entity)
    val periods = FakePeriods()
    val history = FakeHistory()
    val proposals = FakeProposals()
    val recorded = Recording()
    val published = mutableListOf<FilingFrequencyChange>()
    private val statuses =
        object : EntityStatusStore {
            override fun history(entityId: UUID): List<EntityStatusEntry> =
                listOfNotNull(
                    registration?.let {
                        EntityStatusEntry(StatusKind.OBLIGATION_START, it, registrationStartDate = it)
                    },
                )

            override fun insert(
                entityId: UUID,
                entry: EntityStatusEntry,
            ) = Unit
        }
    val planner =
        PeriodPlanner(
            statuses,
            { if (packAvailable) pack() else null },
            { _, _, _ -> NonBusinessDayRule.NO_MOVE },
        )
    val generation = PeriodGeneration(Direct, structure, planner, history, periods, recorded)
    val confirmation =
        ConfirmPeriods(
            Direct,
            structure,
            PeriodAccess(ActionAuthority { _, _, _ -> true }, CallerSource { "identity:USR-1" }),
            PeriodStores(history, proposals, periods),
            object : FilingFrequencyChangePublisher {
                override fun publish(change: FilingFrequencyChange) {
                    published += change
                }
            },
            recorded,
        )

    init {
        history.insert(
            entity.id,
            FrequencyEntry(UUID.randomUUID(), firstEffective, "QUARTERLY", "IRAS-1", HistoryState.ACTIVE),
        )
    }

    fun span(p: StoredPeriod) = PeriodSpanRecord(p.periodRef, p.start, p.end, p.dueDate)

    fun inForceSpans() = periods.inForce(entity.id).map(::span)

    /** A pending MONTHLY proposal from [SPLIT] with PER-004 raised, as `changeFilingFrequency` stores it. */
    fun propose(): ConfirmationPeriod {
        val pending = FrequencyEntry(UUID.randomUUID(), SPLIT, "MONTHLY", "IRAS-2", HistoryState.PENDING)
        val active = history.entries(entity.id).filter { it.state == HistoryState.ACTIVE }
        val plan = planner.plan(entity, active + pending, null, null) as Plan.Periods
        val computed = plan.periods.filter { it.start >= SPLIT }
        history.insert(entity.id, pending)
        proposals.insertProposed(entity.id, pending.id, computed)
        proposals.raise(entity.id, pending.id, RaisedCode.PER_004)
        val first = computed.first { it.start == SPLIT }
        return ConfirmationPeriod(first.periodRef, first.start, first.end, ConfirmationKind.FREQUENCY_CHANGE)
    }
}

class PeriodRecordsChangePublishingTest {
    @Test
    fun `INV-AC-18047 generate that inserts records publishes once with the records in force and the version drawn`() {
        val s = Setup()
        val outcome = s.generation.generate("ENT-1")
        assertTrue(outcome is PeriodOutcome.Done)
        val expected = PeriodRecordsChange("ENT-1", s.structure.drawn.single(), s.inForceSpans())
        assertEquals(listOf(expected), s.recorded.changes)
        assertEquals(
            s.periods
                .inForce(s.entity.id)
                .sortedBy { it.start }
                .map(s::span),
            expected.periods,
        )
        assertEquals(REGISTRATION, expected.periods.first().periodStart)
        assertTrue(expected.periods.size > 1)
    }

    @Test
    fun `INV-AC-9542 a second generate that inserts nothing publishes nothing and leaves the records equal`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        val before = s.periods.all()
        val published = s.recorded.changes.toList()
        val draws = s.structure.drawn.toList()
        s.generation.generate("ENT-1")
        assertEquals(published, s.recorded.changes)
        assertEquals(draws, s.structure.drawn)
        assertEquals(before, s.periods.all())
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-18047 finalPeriod that replaces records publishes once with the set after the change and no superseded record`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.recorded.changes.clear()
        val outcome = s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15))
        assertTrue(outcome is PeriodOutcome.Done)
        val inForce = s.periods.inForce(s.entity.id)
        assertEquals(LocalDate.of(2026, 8, 15), inForce.last().end)
        val expected = PeriodRecordsChange("ENT-1", s.structure.drawn.last(), inForce.map(s::span))
        assertEquals(listOf(expected), s.recorded.changes)
        assertTrue(s.periods.all().any { it.second })
        assertTrue(
            expected.periods.none { span ->
                s.periods.all().any {
                    it.second &&
                        it.first.periodRef == span.periodRef &&
                        it.first.end == span.periodEnd
                }
            },
        )
    }

    @Test
    fun `INV-AC-18047 finalPeriod repeated with the same date publishes nothing`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15))
        val count = s.recorded.changes.size
        s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15))
        assertEquals(count, s.recorded.changes.size)
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-18047 applyProposal publishes once with the set after the replacement besides the FilingFrequencyChange`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.recorded.changes.clear()
        val confirmation = s.propose()
        val request =
            ConfirmRequest(confirmation.periodRef, confirmation.start, confirmation.end, ConfirmKind.FREQUENCY_CHANGE)
        val outcome = s.confirmation.confirm("ENT-1", request)
        assertTrue(outcome is ConfirmOutcome.Done)
        assertEquals(1, s.published.size)
        val expected = PeriodRecordsChange("ENT-1", s.structure.drawn.last(), s.inForceSpans())
        assertEquals(listOf(expected), s.recorded.changes)
        assertTrue(expected.periods.any { it.periodStart == SPLIT && it.periodEnd == LocalDate.of(2026, 7, 31) })
    }

    @Test
    fun `INV-AC-18047 confirmRecord of a single record publishes nothing`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.recorded.changes.clear()
        val first = s.periods.inForce(s.entity.id).first()
        val outcome =
            s.confirmation.confirm("ENT-1", ConfirmRequest(first.periodRef, first.start, first.end, ConfirmKind.FIRST))
        assertTrue(outcome is ConfirmOutcome.Done)
        assertTrue(s.recorded.changes.isEmpty())
    }

    @Test
    fun `INV-AC-18047 a refused confirm publishes nothing`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.recorded.changes.clear()
        val first = s.periods.inForce(s.entity.id).first()
        assertTrue(
            s.confirmation.confirm(
                "ENT-1",
                ConfirmRequest("PRD-none", first.start, first.end, ConfirmKind.FIRST),
            ) is ConfirmOutcome.NotFound,
        )
        assertTrue(
            s.confirmation.confirm(
                "ENT-9",
                ConfirmRequest(first.periodRef, first.start, first.end, ConfirmKind.FIRST),
            ) is ConfirmOutcome.NotFound,
        )
        assertTrue(
            s.confirmation.confirm(
                "ENT-1",
                ConfirmRequest(first.periodRef, first.start, first.end, ConfirmKind.FINAL),
            ) is ConfirmOutcome.Refused,
        )
        assertTrue(s.recorded.changes.isEmpty())
    }

    @Test
    fun `INV-AC-18047 an Invalid generation publishes nothing`() {
        val s = Setup(firstEffective = LocalDate.of(2026, 3, 1))
        assertTrue(s.generation.generate("ENT-1") is PeriodOutcome.Invalid)
        assertTrue(s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15)) is PeriodOutcome.Invalid)
        assertTrue(s.recorded.changes.isEmpty())
        assertTrue(s.structure.drawn.isEmpty())
    }

    @Test
    fun `INV-AC-18047 a Refused generation publishes nothing`() {
        val s = Setup(packAvailable = false)
        assertTrue(s.generation.generate("ENT-1") is PeriodOutcome.Refused)
        assertTrue(s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15)) is PeriodOutcome.Refused)
        assertTrue(s.recorded.changes.isEmpty())
    }

    @Test
    fun `INV-AC-18047 a NotFound entity publishes nothing`() {
        val s = Setup()
        assertTrue(s.generation.generate("ENT-9") is PeriodOutcome.NotFound)
        assertTrue(s.generation.finalPeriod("ENT-9", LocalDate.of(2026, 8, 15)) is PeriodOutcome.NotFound)
        assertTrue(s.recorded.changes.isEmpty())
    }

    @Test
    fun `INV-AC-18047 versions published for one entity strictly rise across the calls`() {
        val s = Setup()
        s.generation.generate("ENT-1")
        s.generation.generate("ENT-1")
        s.generation.finalPeriod("ENT-1", LocalDate.of(2026, 8, 15))
        val versions = s.recorded.changes.map { it.version }
        assertEquals(2, versions.size)
        assertTrue(versions.zipWithNext().all { (a, b) -> a < b })
        assertEquals(s.structure.drawn, versions)
    }

    @Test
    fun `INV-AC-18047 the default publisher is NONE and accepts a change`() {
        PeriodRecordsChangePublisher.NONE.publish(PeriodRecordsChange("ENT-1", 1, emptyList()))
    }

    @Test
    fun `INV-AC-20743 changeOf carries the stored dueDate of each period as filingDueDate`() {
        val moved = LocalDate.of(2026, 8, 14)
        val p1 = stored("2026Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30))
        val p2 = stored("2026Q2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), moved)
        val change = PeriodRecordsChangePublisher.changeOf("E-001", 7, listOf(p1, p2))
        assertEquals(listOf(p1, p2).map { it.dueDate }, change.periods.map { it.filingDueDate })
        assertEquals(moved, change.periods[1].filingDueDate)
        assertEquals(listOf("2026Q1", "2026Q2"), change.periods.map { it.periodRef })
    }

    private fun stored(
        ref: String,
        start: LocalDate,
        end: LocalDate,
        due: LocalDate,
    ) = StoredPeriod(ref, start, end, "F5", due, emptySet())
}
