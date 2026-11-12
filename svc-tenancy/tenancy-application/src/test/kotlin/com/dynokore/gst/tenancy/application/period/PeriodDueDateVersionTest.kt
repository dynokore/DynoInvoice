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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private val DV_EFFECTIVE: LocalDate = LocalDate.of(2026, 1, 1)

private fun dvPack(): CalendarPack {
    val quarters =
        (0 until 4).map {
            StandardWindow(LocalDate.of(2026, it * 3 + 1, 1), YearMonth.of(2026, it * 3 + 3).atEndOfMonth(), "F5")
        }
    return CalendarPack(
        "fixture-dv",
        mapOf(FilingFrequency("QUARTERLY") to quarters),
        "F8",
        mapOf("F5" to DueRule(1, true, 0), "F8" to DueRule(3, true, 0)),
        BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
        null,
    )
}

private class DvStructure(
    private val entities: List<EntityRecord>,
) : StructureStore {
    override fun entity(ref: String): EntityRecord? = entities.firstOrNull { it.ref == ref }

    override fun nextStructureVersion(): Long = 1L

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

/** Counts per (entity, period) like the jOOQ store: one counter shared by every writer of that period. */
private class DvPeriods : PeriodRecordStore {
    val rows = mutableMapOf<UUID, MutableList<StoredPeriod>>()
    private val counters = mutableMapOf<Pair<UUID, String>, Long>()
    var draws = 0

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
    ) = Unit

    override fun confirm(
        entityId: UUID,
        periodRef: String,
    ) = Unit

    override fun setDueDate(
        entityId: UUID,
        periodRef: String,
        dueDate: LocalDate,
    ): Long {
        draws++
        rows.getValue(entityId).replaceAll { if (it.periodRef == periodRef) it.copy(dueDate = dueDate) else it }
        return counters.merge(entityId to periodRef, 1L, Long::plus)!!
    }

    override fun entityRefsWithRecords(): List<String> = emptyList()
}

private class DvExtensions : PeriodExtensionStore {
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

private class DvFacts : FilingFactStore {
    override fun keep(fact: FilingFact): Boolean = true

    override fun forEntity(entityRef: String): Map<String, FilingFact> = emptyMap()
}

private class DvPublisher : PeriodEventPublisher {
    /** The published (entityRef, periodRef, dueDateVersion) in call order. */
    val published = mutableListOf<Triple<String, String, Long>>()

    override fun extended(event: PeriodExtension) = Unit

    override fun dueDateChanged(
        change: PeriodDueDateChange,
        dueDateVersion: Long,
    ) {
        published += Triple(change.entityRef, change.periodRef, dueDateVersion)
    }
}

private class DvHistory : FrequencyHistoryStore {
    override fun entries(entityId: UUID): List<FrequencyEntry> =
        listOf(FrequencyEntry(UUID.randomUUID(), DV_EFFECTIVE, "QUARTERLY", "IRAS-1", HistoryState.ACTIVE))

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

private class DvWorld {
    val a = EntityRecord(UUID.randomUUID(), "ENT-A", "GRP-1", "Acme A", "COMPANY", null, null, "EN", "ACTIVE", false, 1)
    val b = EntityRecord(UUID.randomUUID(), "ENT-B", "GRP-1", "Acme B", "COMPANY", null, null, "EN", "ACTIVE", false, 1)
    val structure = DvStructure(listOf(a, b))
    val periods = DvPeriods()
    val extensions = DvExtensions()
    val publisher = DvPublisher()
    private val statuses =
        object : EntityStatusStore {
            override fun history(entityId: UUID): List<EntityStatusEntry> =
                listOf(
                    EntityStatusEntry(StatusKind.OBLIGATION_START, DV_EFFECTIVE, registrationStartDate = DV_EFFECTIVE),
                )

            override fun insert(
                entityId: UUID,
                entry: EntityStatusEntry,
            ) = Unit
        }
    private val planner = PeriodPlanner(statuses, { dvPack() }, { _, _, _ -> NonBusinessDayRule.NO_MOVE })
    val recomputation = DueDateRecomputation(structure, planner, DvHistory(), periods, extensions, DvFacts(), publisher)
    val record =
        RecordExtension(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            structure,
            PeriodAccess(ActionAuthority { _, _, _ -> true }, CallerSource { "USR-1" }),
            CallerSource { "USR-1" },
            periods,
            extensions,
            publisher,
            recomputation,
            { Instant.parse("2026-01-05T00:00:00Z") },
        )

    /** Stores the first two planned periods of [entity]; the first is stored with a stale due date when [stale]. */
    fun store(
        entity: EntityRecord,
        stale: Set<Int>,
    ): List<StoredPeriod> {
        val plan =
            (
                planner.plan(entity, DvHistory().entries(entity.id), DV_EFFECTIVE, null, DV_EFFECTIVE, dvPack())
                    as Plan.Periods
            ).periods.sortedBy { it.start }.take(2)
        return plan.mapIndexed { i, p ->
            p
                .copy(
                    dueDate =
                        if (i in
                            stale
                        ) {
                            p.dueDate.minusDays(9)
                        } else {
                            p.dueDate
                        },
                ).also { periods.insert(entity.id, it) }
        }
    }

    fun pack(entity: EntityRecord) =
        recomputation.apply(entity, DV_EFFECTIVE, DueDateCause.Pack("iras-calendar", "2", DV_EFFECTIVE))

    fun extend(
        entity: EntityRecord,
        period: StoredPeriod,
        ref: String,
    ) = record.record(
        entity.ref,
        period.periodRef,
        ExtensionRequest(
            ExtensionTarget.FILING_DUE_DATE,
            period.dueDate.plusDays(30),
            null,
            ExtensionScope(ExtensionScopeKind.PERIOD),
            ref,
            "EV-1",
        ),
    )
}

class PeriodDueDateVersionTest {
    @Test
    fun `INV-AC-20668 the first change of a period publishes version 1 and the next 2`() {
        val w = DvWorld()
        val p = w.store(w.a, setOf(0))[0]
        w.pack(w.a)
        w.periods.rows
            .getValue(w.a.id)
            .replaceAll { if (it.periodRef == p.periodRef) it.copy(dueDate = p.dueDate) else it } // stale again
        w.pack(w.a)
        assertEquals(listOf(Triple("ENT-A", p.periodRef, 1L), Triple("ENT-A", p.periodRef, 2L)), w.publisher.published)
    }

    @Test
    fun `INV-AC-20668 a pack change and an extension change of one period share one counter`() {
        val w = DvWorld()
        val p = w.store(w.a, setOf(0))[0]
        w.pack(w.a)
        w.extend(w.a, p, "IRAS-1")
        assertEquals(
            listOf(1L, 2L),
            w.publisher.published
                .filter { it.second == p.periodRef }
                .map { it.third },
        )
    }

    @Test
    fun `INV-AC-20668 a second period of the same entity and the same period of another entity start at 1`() {
        val w = DvWorld()
        val aStored = w.store(w.a, setOf(0, 1))
        val bStored = w.store(w.b, setOf(0))
        w.pack(w.a)
        w.pack(w.b)
        w.extend(w.a, aStored[0], "IRAS-2")
        val byKey = w.publisher.published.groupBy({ it.first to it.second }, { it.third })
        assertEquals(listOf(1L, 2L), byKey[("ENT-A" to aStored[0].periodRef)])
        assertEquals(listOf(1L), byKey[("ENT-A" to aStored[1].periodRef)])
        assertEquals(listOf(1L), byKey[("ENT-B" to bStored[0].periodRef)])
    }

    @Test
    fun `INV-AC-20668 a recomputation that finds no moved date publishes nothing and draws no version`() {
        val w = DvWorld()
        w.store(w.a, emptySet())
        assertTrue(w.pack(w.a).isEmpty())
        assertTrue(w.publisher.published.isEmpty())
        assertEquals(0, w.periods.draws)
    }
}
