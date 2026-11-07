package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The calendar day in Singapore (the filing date and the period state are Singapore dates, INV-Q-16113). */
val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

/**
 * The latest filing status of a period as submission-svc publishes it (PeriodFilingStatusChanged, INV-Q-16113): kept
 * by the greatest [recordedAt] per entity and period. [governingFiledAt] is absent until a filing fact is recorded.
 */
data class FilingFact(
    val entityRef: String,
    val periodRef: String,
    val submissionLate: Boolean,
    val governingFiledAt: Instant?,
    val conflict: Boolean,
    val recordedAt: Instant,
) {
    /** The Singapore calendar date of [governingFiledAt]. */
    val filingDate: LocalDate? get() = governingFiledAt?.atZone(SINGAPORE)?.toLocalDate()
}

/** The replica of the filing status events (insert or replace by the greatest `recordedAt`). */
interface FilingFactStore {
    /** True when the fact was stored; an event with an older or equal `recordedAt` than the kept one is false. */
    fun keep(fact: FilingFact): Boolean

    /** The kept facts of the entity by period reference. */
    fun forEntity(entityRef: String): Map<String, FilingFact>
}

enum class PeriodPosition { PAST, CURRENT, NEXT }

/** One state per record, by the precedence late, filed, due, open (INV-REQ-85 b3). */
enum class PeriodState { OPEN, DUE, FILED, LATE }

data class PeriodRecordView(
    val periodRef: String,
    val start: LocalDate,
    val end: LocalDate,
    val position: PeriodPosition,
    val state: PeriodState,
    val filingDueDate: LocalDate,
    val filingDate: LocalDate?,
    val extensionGranted: Boolean,
)

/** Where the previous page ended: records are ordered by period start, then period reference (the sort of the list). */
data class PagePosition(
    val start: LocalDate,
    val periodRef: String,
)

sealed interface PeriodListOutcome {
    data class Done(
        val entityRef: String,
        val items: List<PeriodRecordView>,
        val more: Boolean,
    ) : PeriodListOutcome

    data object NotFound : PeriodListOutcome
}

/**
 * listPeriodRecords (INV-REQ-85): the past, current and next period records of an entity, each with its position, its
 * one state, its filing due date, its filing date and the extension flag. The state follows the single definition of
 * INV-NOTE-3 FR-AG.15: late (the filing due date passed without a filing fact, a filing made after it, or documents
 * past their submission due date) over filed over due over open.
 */
class ListPeriodRecords(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val periods: PeriodRecordStore,
    private val extensions: PeriodExtensionStore,
    private val facts: FilingFactStore,
) {
    fun list(
        entityRef: String,
        limit: Int,
        today: LocalDate,
        after: PagePosition? = null,
    ): PeriodListOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction PeriodListOutcome.NotFound
            val known = facts.forEntity(entityRef)
            val extended = extensions.extendedPeriods(entity.id)
            val stored = periods.inForce(entity.id)
            val next = stored.firstOrNull { it.start.isAfter(today) }
            val shown = stored.filter { next == null || !it.start.isAfter(next.start) }
            val views =
                shown
                    .sortedWith(compareBy({ it.start }, { it.periodRef }))
                    .filter { after == null || isAfter(it, after) }
                    .map { view(it, today, next, known[it.periodRef], it.periodRef in extended) }
            PeriodListOutcome.Done(entityRef, views.take(limit), views.size > limit)
        }

    private fun isAfter(
        period: StoredPeriod,
        position: PagePosition,
    ): Boolean =
        period.start.isAfter(position.start) ||
            (period.start == position.start && period.periodRef > position.periodRef)

    private fun view(
        period: StoredPeriod,
        today: LocalDate,
        next: StoredPeriod?,
        fact: FilingFact?,
        extended: Boolean,
    ): PeriodRecordView {
        val filingDate = fact?.filingDate
        val position =
            when {
                period.end.isBefore(today) -> PeriodPosition.PAST
                period.start == next?.start -> PeriodPosition.NEXT
                else -> PeriodPosition.CURRENT
            }
        val pastDue = if (filingDate == null) period.dueDate.isBefore(today) else filingDate.isAfter(period.dueDate)
        val late = pastDue || fact?.submissionLate == true
        val state =
            when {
                late -> PeriodState.LATE
                filingDate != null -> PeriodState.FILED
                period.end.isBefore(today) -> PeriodState.DUE
                else -> PeriodState.OPEN
            }
        return PeriodRecordView(
            period.periodRef,
            period.start,
            period.end,
            position,
            state,
            period.dueDate,
            filingDate,
            extended,
        )
    }
}

/**
 * Recomputes the filing due dates of an entity's unfiled periods (INV-REQ-1323, INV-REQ-88): the pack in force, the
 * VP-62 rule resolved on the effective date and the generator of the calendar-and-periods library give the due date
 * each stored period would have; a period that is unfiled and has a stored due date on or after the effective date
 * takes the later of that date and the greatest filing date its extension records grant (INV-Q-16677). Acknowledged
 * documents keep their own dates (they are not periods here).
 */
@Suppress("LongParameterList") // one port per collaborator of the use case, as in the other period use cases
class DueDateRecomputation(
    private val structure: StructureStore,
    private val planner: PeriodPlanner,
    private val history: FrequencyHistoryStore,
    private val periods: PeriodRecordStore,
    private val extensions: PeriodExtensionStore,
    private val facts: FilingFactStore,
    private val publisher: PeriodEventPublisher,
) {
    /** The changes the recomputation would make, with nothing written (the dry run reads this). */
    fun changes(
        entity: EntityRecord,
        from: LocalDate,
        pack: CalendarPack?,
        cause: DueDateCause,
    ): List<PeriodDueDateChange> {
        val planned = plannedByStart(entity, from, pack) ?: return emptyList()
        val filed = facts.forEntity(entity.ref).filterValues { it.governingFiledAt != null }.keys
        val granted = grantedDates(entity.id)
        return periods
            .inForce(entity.id)
            .filter { it.periodRef !in filed && !it.dueDate.isBefore(from) }
            .mapNotNull { stored ->
                val rule = planned[stored.start]?.dueDate ?: return@mapNotNull null
                inForce(rule, granted[stored.periodRef])
                    .takeIf { it != stored.dueDate }
                    ?.let { PeriodDueDateChange(entity.ref, stored.periodRef, stored.dueDate, it, cause) }
            }
    }

    private fun plannedByStart(
        entity: EntityRecord,
        from: LocalDate,
        pack: CalendarPack?,
    ): Map<LocalDate, StoredPeriod>? {
        val active = history.entries(entity.id).filter { it.state == HistoryState.ACTIVE }
        return (
            planner.plan(
                entity,
                active,
                from,
                null,
                from,
                pack,
            ) as? Plan.Periods
        )?.periods?.associateBy { it.start }
    }

    /** The one definition of the date in force: the later of the rule date and the greatest granted date. */
    private fun inForce(
        rule: LocalDate,
        granted: LocalDate?,
    ): LocalDate = granted?.let { maxOf(rule, it) } ?: rule

    /**
     * The filing due date in force for [period] on [on], the function [changes] uses (INV-Q-16677, INV-Q-16808): the
     * rule date the planner gives under the rule and pack in force on [on] (the stored date when the entity cannot be
     * planned), or the later of it and the greatest granted date of the period's records, including a record just
     * written in the caller's transaction.
     */
    fun dueDateInForce(
        entity: EntityRecord,
        period: StoredPeriod,
        on: LocalDate,
    ): LocalDate {
        val rule = plannedByStart(entity, on, null)?.get(period.start)?.dueDate ?: period.dueDate
        return inForce(rule, grantedDates(entity.id)[period.periodRef])
    }

    /**
     * The greatest `newDueDate` of each period's extension records whose target includes the filing due date
     * (INV-Q-16677): a granted extension is never lost or overtaken. A submission-due-date extension is not used.
     */
    private fun grantedDates(entityId: UUID): Map<String, LocalDate> =
        extensions
            .extendedPeriods(entityId)
            .mapNotNull { ref ->
                extensions
                    .forPeriod(entityId, ref)
                    .mapNotNull { it.request.newFilingDueDate }
                    .maxOrNull()
                    ?.let { ref to it }
            }.toMap()

    /** Applies the changes in the caller's transaction: one stored date and one PeriodDueDateChanged per period. */
    fun apply(
        entity: EntityRecord,
        from: LocalDate,
        cause: DueDateCause,
    ): List<PeriodDueDateChange> =
        changes(entity, from, null, cause).onEach {
            periods.setDueDate(entity.id, it.periodRef, it.newFilingDueDate)
            publisher.dueDateChanged(it)
        }

    /** Applies the recomputation to the entity named [entityRef]; absent entities change nothing. */
    fun applyTo(
        entityRef: String,
        from: LocalDate,
        cause: DueDateCause,
    ): List<PeriodDueDateChange> = structure.entity(entityRef)?.let { apply(it, from, cause) }.orEmpty()
}

/** A staged calendar pack release, decoded, with the date its payload says it takes effect. */
data class StagedCalendar(
    val pack: CalendarPack,
    val effectiveFrom: LocalDate,
)

/** The staged versions of calendar packs, by pack identifier and version (staged, not necessarily activated). */
fun interface StagedCalendarPacks {
    fun find(
        packIdentifier: String,
        version: String,
    ): StagedCalendar?
}

/** One due date a pack release would move for one entity (INV-REQ-88). */
data class DueDateMove(
    val entityRef: String,
    val periodRef: String,
    val oldFilingDueDate: LocalDate,
    val newFilingDueDate: LocalDate,
)

/**
 * The dry-run delta of a staged calendar pack release (INV-REQ-88): per entity, the period whose due date
 * would move on activation, with the old and the new date. It reads and writes nothing: no record changes and no event
 * is published; null when the release is not staged here as a calendar pack.
 */
class PackDeltaPreview(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val periods: PeriodRecordStore,
    private val recomputation: DueDateRecomputation,
    private val staged: StagedCalendarPacks,
) {
    fun delta(
        packIdentifier: String,
        version: String,
    ): List<DueDateMove>? =
        tx.inTransaction {
            val release = staged.find(packIdentifier, version) ?: return@inTransaction null
            val cause = DueDateCause.Pack(packIdentifier, version, release.effectiveFrom)
            periods
                .entityRefsWithRecords()
                .mapNotNull { structure.entity(it) }
                .flatMap { recomputation.changes(it, release.effectiveFrom, release.pack, cause) }
                .map { DueDateMove(it.entityRef, it.periodRef, it.oldFilingDueDate, it.newFilingDueDate) }
        }

    /**
     * The delta of one entity, a page of it (INV-REQ-88, INV-AC-16823): the moves [DueDateRecomputation.changes] gives
     * with the pack cause, which are the ones the activation publishes for that entity, ordered by `periodRef`
     * ascending. [after] is the last `periodRef` of the previous page (absent for the first page); [limit] is 1 to
     * [MAX_PAGE]. Null when the release is not staged as a calendar pack here or the entity is unknown. Writes nothing.
     */
    fun deltaPage(
        entityRef: String,
        packIdentifier: String,
        version: String,
        limit: Int,
        after: String? = null,
    ): PackDeltaPage? {
        require(limit in 1..MAX_PAGE) { "limit must be 1 to $MAX_PAGE" }
        return tx.inTransaction {
            val release = staged.find(packIdentifier, version) ?: return@inTransaction null
            val entity = structure.entity(entityRef) ?: return@inTransaction null
            val cause = DueDateCause.Pack(packIdentifier, version, release.effectiveFrom)
            val moves =
                recomputation
                    .changes(entity, release.effectiveFrom, release.pack, cause)
                    .map { DueDateMove(it.entityRef, it.periodRef, it.oldFilingDueDate, it.newFilingDueDate) }
                    .sortedBy { it.periodRef }
                    .filter { after == null || it.periodRef > after }
            PackDeltaPage(moves.take(limit), moves.size > limit)
        }
    }

    companion object {
        /** The greatest page size of [deltaPage]. */
        const val MAX_PAGE = 500
    }
}

/** One page of the delta of an entity: [more] is true when another page follows [items]. */
data class PackDeltaPage(
    val items: List<DueDateMove>,
    val more: Boolean,
)

/**
 * The effect of a calendar pack activation on the stored periods (INV-REQ-88, INV-AC-110): when [version] is a staged
 * calendar pack and the pack in force on [effectiveFrom] (as `PackRegistry.resolve` answers it) is that version, every
 * entity's unfiled periods are recomputed from it and each moved due date is published. A pack that pins the
 * non-business-day rule overrides the entity's VP-62 setting here, by data alone. RulePackStaged and RulePackActivated
 * both run this one check ([onStaged] for every recorded activation of the version), which changes nothing when the
 * stored dates already equal the recomputed ones, so either order and any redelivery give the same result
 * (INV-Q-16814).
 */
class PackActivationRecompute(
    private val replica: CalendarPackReplica,
    private val periods: PeriodRecordStore,
    private val recomputation: DueDateRecomputation,
    private val staged: StagedCalendarPacks,
    private val source: CalendarPackSource,
) {
    /** The staging of a version: the check for each activation already recorded for it (none: nothing changes). */
    fun onStaged(
        packIdentifier: String,
        version: String,
    ): List<PeriodDueDateChange> =
        replica.activations(packIdentifier, version).flatMap { onActivated(packIdentifier, version, it) }

    fun onActivated(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): List<PeriodDueDateChange> {
        val applies = staged.find(packIdentifier, version) != null && source.inForce(effectiveFrom)?.version == version
        val cause = DueDateCause.Pack(packIdentifier, version, effectiveFrom)
        return if (applies) {
            periods.entityRefsWithRecords().flatMap { recomputation.applyTo(it, effectiveFrom, cause) }
        } else {
            emptyList()
        }
    }
}
