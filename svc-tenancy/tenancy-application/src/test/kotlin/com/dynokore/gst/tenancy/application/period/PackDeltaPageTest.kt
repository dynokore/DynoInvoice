package com.dynokore.gst.tenancy.application.period

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private val PD_QUARTERLY = FilingFrequency("QUARTERLY")
private val PD_EFFECTIVE: LocalDate = LocalDate.of(2026, 1, 1)
private const val PACK = "iras-calendar"
private const val VERSION = "2"

private fun pdPack(): CalendarPack {
    val quarters =
        (2026..2027).flatMap { y ->
            (0 until 4).map {
                StandardWindow(LocalDate.of(y, it * 3 + 1, 1), YearMonth.of(y, it * 3 + 3).atEndOfMonth(), "F5")
            }
        }
    return CalendarPack(
        "fixture-2",
        mapOf(PD_QUARTERLY to quarters),
        "F8",
        mapOf("F5" to DueRule(1, true, 0), "F8" to DueRule(3, true, 0)),
        BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
        null,
    )
}

private object PdDirect : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

private class Structure(
    private val entities: List<EntityRecord>,
) : StructureStore {
    val drawn = mutableListOf<Long>()

    override fun entity(ref: String): EntityRecord? = entities.firstOrNull { it.ref == ref }

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

private class PdPeriods : PeriodRecordStore {
    val rows = mutableMapOf<UUID, MutableList<StoredPeriod>>()
    var writes = 0

    override fun inForce(entityId: UUID): List<StoredPeriod> = rows[entityId].orEmpty().sortedBy { it.start }

    override fun insert(
        entityId: UUID,
        period: StoredPeriod,
    ) {
        rows.getOrPut(entityId) { mutableListOf() } += period
    }

    override fun supersedeFrom(
        entityId: UUID,
        start: LocalDate,
    ) {
        writes++
    }

    override fun confirm(
        entityId: UUID,
        periodRef: String,
    ) {
        writes++
    }

    override fun setDueDate(
        entityId: UUID,
        periodRef: String,
        dueDate: LocalDate,
    ) {
        writes++
    }

    override fun entityRefsWithRecords(): List<String> = emptyList()

    fun snapshot(): Map<UUID, List<StoredPeriod>> = rows.mapValues { it.value.toList() }
}

private class Extensions : PeriodExtensionStore {
    val records = mutableListOf<Pair<UUID, ExtensionRecord>>()

    override fun forPeriod(
        entityId: UUID,
        periodRef: String,
    ): List<ExtensionRecord> =
        records.filter { it.first == entityId && it.second.periodRef == periodRef }.map { it.second }

    override fun extendedPeriods(entityId: UUID): Set<String> =
        records.filter { it.first == entityId }.map { it.second.periodRef }.toSet()

    override fun insert(
        entityId: UUID,
        record: ExtensionRecord,
    ) {
        records += entityId to record
    }
}

private class Facts : FilingFactStore {
    val facts = mutableListOf<FilingFact>()

    override fun keep(fact: FilingFact): Boolean = facts.add(fact)

    override fun forEntity(entityRef: String): Map<String, FilingFact> =
        facts.filter { it.entityRef == entityRef }.associateBy { it.periodRef }
}

private class Outbox : PeriodEventPublisher {
    val events = mutableListOf<Any>()

    override fun extended(event: PeriodExtension) {
        events += event
    }

    override fun dueDateChanged(change: PeriodDueDateChange) {
        events += change
    }
}

private class History : FrequencyHistoryStore {
    override fun entries(entityId: UUID): List<FrequencyEntry> =
        listOf(FrequencyEntry(UUID.randomUUID(), PD_EFFECTIVE, "QUARTERLY", "IRAS-1", HistoryState.ACTIVE))

    override fun insert(
        entityId: UUID,
        entry: FrequencyEntry,
    ) = Unit

    override fun setState(
        entityId: UUID,
        id: UUID,
        state: HistoryState,
    ) = Unit
}

private fun entity(ref: String) =
    EntityRecord(UUID.randomUUID(), ref, "GRP-1", "Acme $ref", "COMPANY", null, null, ref.take(2), "ACTIVE", false, 1)

private class World(
    stagedHere: Boolean = true,
) {
    val a = entity("ENT-A")
    val b = entity("ENT-B")
    val structure = Structure(listOf(a, b))
    val periods = PdPeriods()
    val extensions = Extensions()
    val facts = Facts()
    val outbox = Outbox()
    private val statuses =
        object : EntityStatusStore {
            override fun history(entityId: UUID): List<EntityStatusEntry> =
                listOf(
                    EntityStatusEntry(StatusKind.OBLIGATION_START, PD_EFFECTIVE, registrationStartDate = PD_EFFECTIVE),
                )

            override fun insert(
                entityId: UUID,
                entry: EntityStatusEntry,
            ) = Unit
        }
    val planner = PeriodPlanner(statuses, { pdPack() }, { _, _, _ -> NonBusinessDayRule.NO_MOVE })
    private val recomputation =
        DueDateRecomputation(structure, planner, History(), periods, extensions, facts, outbox)
    val preview =
        PackDeltaPreview(
            PdDirect,
            structure,
            periods,
            recomputation,
            { id, v -> if (stagedHere && id == PACK && v == VERSION) StagedCalendar(pdPack(), PD_EFFECTIVE) else null },
        )

    /** The periods the staged pack gives the entity, in start order. */
    fun planned(entity: EntityRecord): List<StoredPeriod> {
        val active = History().entries(entity.id)
        return (planner.plan(entity, active, PD_EFFECTIVE, null, PD_EFFECTIVE, pdPack()) as Plan.Periods)
            .periods
            .filter { it.start >= PD_EFFECTIVE }
            .sortedBy { it.start }
            .take(4)
    }

    /** Stores the entity's first four planned periods; the start indexes in [moving] get a stale due date. */
    fun store(
        entity: EntityRecord,
        moving: Set<Int>,
    ): List<StoredPeriod> =
        planned(entity).mapIndexed { i, p ->
            p
                .copy(
                    dueDate =
                        if (i in
                            moving
                        ) {
                            p.dueDate.minusDays(9)
                        } else {
                            p.dueDate
                        },
                ).also { periods.insert(entity.id, it) }
        }

    fun move(
        stored: StoredPeriod,
        entity: EntityRecord,
    ): DueDateMove {
        val now = planned(entity).first { it.start == stored.start }
        return DueDateMove(entity.ref, stored.periodRef, stored.dueDate, now.dueDate)
    }

    fun filed(
        entity: EntityRecord,
        periodRef: String,
    ) = facts.facts.add(
        FilingFact(entity.ref, periodRef, false, Instant.parse("2026-02-01T00:00:00Z"), false, Instant.now()),
    )
}

class PackDeltaPageTest {
    @Test
    fun `INV-AC-16823 lists the moving periods of one entity in periodRef order, paged, and writes nothing`() {
        val w = World()
        val aStored = w.store(w.a, setOf(0, 2))
        val bStored = w.store(w.b, setOf(0, 1, 3))
        val expected = listOf(0, 2).map { w.move(aStored[it], w.a) }.sortedBy { it.periodRef }
        val periodsBefore = w.periods.snapshot()
        val extensionsBefore = w.extensions.records.toList()
        val outboxBefore = w.outbox.events.toList()

        val all = w.preview.deltaPage("ENT-A", PACK, VERSION, 10)!!
        assertEquals(expected, all.items)
        assertEquals(false, all.more)
        assertTrue(all.items.none { it.entityRef != "ENT-A" })
        assertTrue(all.items.none { m -> bStored.any { it.periodRef == m.periodRef } })
        assertTrue(all.items.none { it.periodRef == aStored[1].periodRef || it.periodRef == aStored[3].periodRef })

        val first = w.preview.deltaPage("ENT-A", PACK, VERSION, 1)!!
        assertEquals(listOf(expected[0]), first.items)
        assertEquals(true, first.more)
        val second = w.preview.deltaPage("ENT-A", PACK, VERSION, 1, first.items.last().periodRef)!!
        assertEquals(listOf(expected[1]), second.items)
        assertEquals(false, second.more)
        val past = w.preview.deltaPage("ENT-A", PACK, VERSION, 1, second.items.last().periodRef)!!
        assertEquals(PackDeltaPage(emptyList(), false), past)
        assertEquals(
            PackDeltaPage(listOf(expected[1]), false),
            w.preview.deltaPage("ENT-A", PACK, VERSION, 5, expected[0].periodRef),
        )

        assertEquals(periodsBefore, w.periods.snapshot())
        assertEquals(0, w.periods.writes)
        assertEquals(extensionsBefore, w.extensions.records)
        assertEquals(outboxBefore, w.outbox.events)
    }

    @Test
    fun `INV-AC-16823 a release not staged and an unknown entity give null, a bad limit is refused`() {
        val w = World()
        w.store(w.a, setOf(0))
        assertNull(w.preview.deltaPage("ENT-A", PACK, "9", 5))
        assertNull(w.preview.deltaPage("ENT-A", "other", VERSION, 5))
        assertNull(w.preview.deltaPage("ENT-X", PACK, VERSION, 5))
        assertNull(World(stagedHere = false).preview.deltaPage("ENT-A", PACK, VERSION, 5))
        assertThrows(IllegalArgumentException::class.java) { w.preview.deltaPage("ENT-A", PACK, VERSION, 0) }
        assertThrows(IllegalArgumentException::class.java) { w.preview.deltaPage("ENT-A", PACK, VERSION, 501) }
        assertEquals(
            1,
            w.preview
                .deltaPage("ENT-A", PACK, VERSION, 500)!!
                .items.size,
        )
    }

    @Test
    fun `INV-AC-16823 a filed period is not listed and an extension granted later keeps its date`() {
        val w = World()
        val stored = w.store(w.a, setOf(0, 1, 2))
        w.filed(w.a, stored[1].periodRef)
        val granted = w.planned(w.a)[2].dueDate.plusDays(20)
        w.extensions.insert(
            w.a.id,
            ExtensionRecord(
                stored[2].periodRef,
                ExtensionRequest(
                    ExtensionTarget.FILING_DUE_DATE,
                    granted,
                    null,
                    ExtensionScope(ExtensionScopeKind.PERIOD),
                    "IRAS-X",
                    "EV-1",
                ),
                null,
                "USR-1",
                Instant.parse("2026-01-05T00:00:00Z"),
            ),
        )
        val listed = w.preview.deltaPage("ENT-A", PACK, VERSION, 10)!!.items
        assertEquals(listOf(stored[0].periodRef, stored[2].periodRef).sorted(), listed.map { it.periodRef })
        assertEquals(granted, listed.first { it.periodRef == stored[2].periodRef }.newFilingDueDate)
    }
}
